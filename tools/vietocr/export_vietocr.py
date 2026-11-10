"""Tạo model VietOCR (vgg_transformer) dạng ONNX cho core:ocr.

Đầu ra (đặt vào core/ocr/src/main/assets/vietocr/):
  vietocr_encoder.onnx  image float32 [1,3,32,W] (0..1)
                        -> cross_k_i, cross_v_i float32 [8,S,32] (i = 0..5): K/V cross-attention của từng lớp decoder
  vietocr_decoder.onnx  một bước giải mã:
                        token int64 [1,1], position int64 [1], past_i float32 [P,1,256], cross_k_i, cross_v_i
                        -> logits float32 [1,V], present_i float32 [P+1,1,256]
                        past_i là đầu vào lớp i của các vị trí trước (P = 0 ở bước đầu). Decoder nhân quả nên
                        trạng thái cũ không đổi; mỗi bước chỉ tính vị trí mới thay vì chạy lại cả chuỗi.
  vietocr_vocab.txt     bảng ký tự, id = 4 + vị trí ký tự (0 pad, 1 sos, 2 eos, 3 mask)

Lượng tử hóa:
  - Conv của encoder: tĩnh (QDQ, per-channel, hiệu chuẩn MinMax trên các dòng chữ cắt từ ảnh trang).
    Lượng tử động Conv làm tăng lỗi dấu rõ rệt nên không dùng.
  - MatMul/Gemm: động int8, trừ lớp ra `fc` của decoder (lượng tử lớp này làm nhầm ký tự hiếm, "ấ" thành "t").

Cách dùng:
  pip install vietocr --no-deps einops pyyaml requests gdown torch torchvision onnx onnxruntime rapidocr pillow
  python export_vietocr.py <thư mục ra> <thư mục ảnh trang hiệu chuẩn>
"""
import glob
import math
import os
import shutil
import sys

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxruntime.quantization import (CalibrationDataReader, CalibrationMethod, QuantFormat, QuantType,
                                      quantize_dynamic, quantize_static)
from PIL import Image
from torch import nn
from vietocr.tool.config import Cfg
from vietocr.tool.predictor import Predictor

IMAGE_HEIGHT, MIN_WIDTH, MAX_WIDTH = 32, 32, 512
LAYERS, D_MODEL = 6, 256
CROSS_NAMES = [f"cross_{kind}_{i}" for i in range(LAYERS) for kind in ("k", "v")]
PAST_NAMES = [f"past_{i}" for i in range(LAYERS)]
PRESENT_NAMES = [f"present_{i}" for i in range(LAYERS)]


class ExportMHA(nn.MultiheadAttention):
    """Attention tương đương bản gốc với batch = 1, reshape bằng -1 để exporter giữ độ dài chuỗi động."""

    def forward(self, query, key, value, key_padding_mask=None, need_weights=False, attn_mask=None,
                average_attn_weights=True, is_causal=False):
        e, h = self.embed_dim, self.num_heads
        w, b = self.in_proj_weight, self.in_proj_bias
        q = heads(nn.functional.linear(query, w[:e], b[:e]), h)
        k = heads(nn.functional.linear(key, w[e:2 * e], b[e:2 * e]), h)
        v = heads(nn.functional.linear(value, w[2 * e:], b[2 * e:]), h)
        return self.out_proj(attention(q, k, v, attn_mask, e)), None


def heads(x, h):
    """[L, 1, E] -> [h, L, E/h]."""
    return x.reshape(-1, h, x.shape[-1] // h).transpose(0, 1)


def attention(q, k, v, mask, e):
    """Scaled dot-product trên [h, L, d] -> [L, 1, E]."""
    scores = torch.matmul(q, k.transpose(1, 2)) / math.sqrt(q.shape[-1])
    if mask is not None:
        scores = scores + mask
    return torch.matmul(torch.softmax(scores, dim=-1), v).transpose(0, 1).reshape(-1, 1, e)


class Encoder(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, image):
        # VGG.forward gốc dùng permute(-1, 0, 1); ONNX không nhận chỉ số âm nên viết lại.
        vgg = self.model.cnn.model
        conv = vgg.last_conv_1x1(vgg.features(image)).transpose(2, 3).flatten(2)
        memory = self.model.transformer.forward_encoder(conv.permute(2, 0, 1))
        outputs = []
        for layer in self.model.transformer.transformer.decoder.layers:
            attn = layer.multihead_attn
            e, h = attn.embed_dim, attn.num_heads
            w, b = attn.in_proj_weight, attn.in_proj_bias
            outputs.append(heads(nn.functional.linear(memory, w[e:2 * e], b[e:2 * e]), h))
            outputs.append(heads(nn.functional.linear(memory, w[2 * e:], b[2 * e:]), h))
        return tuple(outputs)


class StepDecoder(nn.Module):
    """Một bước nn.TransformerDecoder (post-norm, ReLU) chỉ cho vị trí mới, tái dùng trạng thái các vị trí trước."""

    def __init__(self, model):
        super().__init__()
        self.lt = model.transformer

    def forward(self, token, position, *states):
        pasts, cross = states[:LAYERS], states[LAYERS:]
        lt = self.lt
        x = lt.embed_tgt(token) * math.sqrt(lt.d_model) + lt.pos_enc.pe.index_select(0, position)
        presents = []
        for i, layer in enumerate(lt.transformer.decoder.layers):
            seq = torch.cat([pasts[i], x], dim=0)
            presents.append(seq)
            sa = layer.self_attn
            e, h = sa.embed_dim, sa.num_heads
            w, b = sa.in_proj_weight, sa.in_proj_bias
            q = heads(nn.functional.linear(x, w[:e], b[:e]), h)
            k = heads(nn.functional.linear(seq, w[e:2 * e], b[e:2 * e]), h)
            v = heads(nn.functional.linear(seq, w[2 * e:], b[2 * e:]), h)
            x = layer.norm1(x + sa.out_proj(attention(q, k, v, None, e)))
            ca = layer.multihead_attn
            cw, cb = ca.in_proj_weight, ca.in_proj_bias
            q = heads(nn.functional.linear(x, cw[:e], cb[:e]), h)
            x = layer.norm2(x + ca.out_proj(attention(q, cross[2 * i], cross[2 * i + 1], None, e)))
            x = layer.norm3(x + layer.linear2(torch.relu(layer.linear1(x))))
        x = lt.transformer.decoder.norm(x)
        return (lt.fc(x)[0],) + tuple(presents)


class LineReader(CalibrationDataReader):
    """Cắt dòng chữ từ ảnh trang (dò dòng bằng RapidOCR) và tiền xử lý giống app (resize BOX)."""

    def __init__(self, page_dir):
        from rapidocr import LangDet, ModelType, OCRVersion, RapidOCR
        det = RapidOCR(params={"Det.ocr_version": OCRVersion.PPOCRV6, "Det.model_type": ModelType.SMALL,
                               "Det.lang_type": LangDet.CH, "Global.use_cls": False})
        items = []
        for path in sorted(glob.glob(os.path.join(page_dir, "*.jpg"))):
            img = Image.open(path)
            for box in det(path, use_rec=False).boxes:
                xs, ys = [p[0] for p in box], [p[1] for p in box]
                pad = (max(ys) - min(ys)) * 0.15
                crop = img.crop((max(0, min(xs) - pad), max(0, min(ys) - pad), max(xs) + pad, max(ys) + pad))
                crop = crop.convert("RGB")
                width = math.ceil(int(IMAGE_HEIGHT * crop.width / crop.height) / 10) * 10
                width = min(max(width, MIN_WIDTH), MAX_WIDTH)
                x = np.asarray(crop.resize((width, IMAGE_HEIGHT), Image.BOX), dtype=np.float32) / 255
                items.append({"image": x.transpose(2, 0, 1)[None]})
        print("Số dòng hiệu chuẩn:", len(items))
        self.iterator = iter(items)

    def get_next(self):
        return next(self.iterator, None)


def check_step_decoder(model, encoder, decoder):
    """So từng bước giải mã với decoder gốc chạy lại cả chuỗi."""
    image = torch.rand(1, 3, IMAGE_HEIGHT, 320)
    tokens = [1, 40, 50, 60, 7, 90, 33]
    with torch.no_grad():
        memory = model.transformer.forward_encoder(model.cnn(image))
        cross = encoder(image)
        pasts = [torch.zeros(0, 1, D_MODEL) for _ in range(LAYERS)]
        for step in range(len(tokens)):
            logits, *pasts = decoder(torch.tensor([[tokens[step]]]), torch.tensor([step]), *pasts, *cross)
            full, _ = model.transformer.forward_decoder(torch.tensor(tokens[:step + 1])[:, None], memory)
            assert torch.allclose(logits, full[:, -1, :], atol=1e-3), f"Decoder từng bước lệch ở bước {step}"


def check_onnx(encoder, decoder, tmp):
    """So ONNX fp32 với PyTorch ở độ rộng khác lúc export, bắt đầu từ trạng thái rỗng (P = 0)."""
    image = torch.rand(1, 3, IMAGE_HEIGHT, 148)
    enc = ort.InferenceSession(f"{tmp}/encoder_fp32.onnx")
    dec = ort.InferenceSession(f"{tmp}/decoder_fp32.onnx")
    feeds = dict(zip(CROSS_NAMES, enc.run(None, {"image": image.numpy()})))
    with torch.no_grad():
        cross = encoder(image)
    pasts_o = [np.zeros((0, 1, D_MODEL), dtype=np.float32) for _ in range(LAYERS)]
    pasts_t = [torch.zeros(0, 1, D_MODEL) for _ in range(LAYERS)]
    worst = 0.0
    for step, token in enumerate([1, 40, 50, 60, 7]):
        out = dec.run(None, {"token": np.array([[token]], dtype=np.int64), "position": np.array([step], dtype=np.int64),
                             **dict(zip(PAST_NAMES, pasts_o)), **feeds})
        with torch.no_grad():
            ref, *pasts_t = decoder(torch.tensor([[token]]), torch.tensor([step]), *pasts_t, *cross)
        worst = max(worst, float(np.abs(out[0] - ref.numpy()).max()))
        pasts_o = out[1:]
    print("Sai lệch ONNX fp32 so với PyTorch:", worst)


def main():
    out_dir, calib_dir = sys.argv[1], sys.argv[2]
    tmp = os.path.join(out_dir, "_tmp")
    os.makedirs(tmp, exist_ok=True)

    cfg = Cfg.load_config_from_name("vgg_transformer")
    cfg["device"] = "cpu"
    model = Predictor(cfg).model.eval()
    torch.backends.mha.set_fastpath_enabled(False)
    encoder, decoder = Encoder(model).eval(), StepDecoder(model).eval()
    for m in model.modules():
        if type(m) is nn.MultiheadAttention:
            m.__class__ = ExportMHA
    check_step_decoder(model, encoder, decoder)

    image = torch.rand(1, 3, IMAGE_HEIGHT, 320)
    with torch.no_grad():
        cross = encoder(image)
    pasts = [torch.zeros(3, 1, D_MODEL) for _ in range(LAYERS)]
    export = dict(opset_version=17, do_constant_folding=True, dynamo=False)
    torch.onnx.export(encoder, (image,), f"{tmp}/encoder_fp32.onnx", input_names=["image"], output_names=CROSS_NAMES,
                      dynamic_axes={"image": {3: "width"}, **{n: {1: "seq"} for n in CROSS_NAMES}}, **export)
    torch.onnx.export(decoder, (torch.tensor([[5]]), torch.tensor([3]), *pasts, *cross), f"{tmp}/decoder_fp32.onnx",
                      input_names=["token", "position"] + PAST_NAMES + CROSS_NAMES,
                      output_names=["logits"] + PRESENT_NAMES,
                      dynamic_axes={**{n: {0: "past"} for n in PAST_NAMES}, **{n: {0: "steps"} for n in PRESENT_NAMES},
                                    **{n: {1: "seq"} for n in CROSS_NAMES}}, **export)
    check_onnx(encoder, decoder, tmp)

    quantize_static(f"{tmp}/encoder_fp32.onnx", f"{tmp}/encoder_conv.onnx", LineReader(calib_dir),
                    quant_format=QuantFormat.QDQ, op_types_to_quantize=["Conv"], per_channel=True,
                    activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8,
                    calibrate_method=CalibrationMethod.MinMax)
    quantize_dynamic(f"{tmp}/encoder_conv.onnx", f"{out_dir}/vietocr_encoder.onnx", weight_type=QuantType.QInt8,
                     op_types_to_quantize=["MatMul", "Gemm"])
    fc_nodes = [n.name for n in onnx.load(f"{tmp}/decoder_fp32.onnx").graph.node if n.name.startswith("/fc/")]
    print("Giữ fp32:", fc_nodes)
    quantize_dynamic(f"{tmp}/decoder_fp32.onnx", f"{out_dir}/vietocr_decoder.onnx", weight_type=QuantType.QInt8,
                     op_types_to_quantize=["MatMul", "Gemm"], nodes_to_exclude=fc_nodes)
    with open(f"{out_dir}/vietocr_vocab.txt", "w", encoding="utf-8", newline="") as f:
        f.write(cfg["vocab"])
    shutil.rmtree(tmp)
    for name in sorted(os.listdir(out_dir)):
        print(f"{name:24s} {os.path.getsize(os.path.join(out_dir, name)) / 2 ** 20:6.1f} MiB")


if __name__ == "__main__":
    main()
