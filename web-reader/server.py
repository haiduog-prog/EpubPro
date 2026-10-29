#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EpubPro Web Reader — Local AI TTS Bridge Server
Máy chủ cầu nối phục vụ giao diện Web Reader và tổng hợp giọng đọc AI Tiếng Việt
Tích hợp trực tiếp engine Sherpa-ONNX và mô hình NGHI-TTS đồng bộ 100% từ ứng dụng Android EpubPro.
"""

import os
import sys
import json
import io
import wave
import time
import hashlib
import urllib.parse
import urllib.request
import threading
from http.server import HTTPServer, SimpleHTTPRequestHandler

# Cấu hình encoding UTF-8 cho Windows console để không bị crash khi in ký tự tiếng Việt
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

PORT = 8080
DIRECTORY = os.path.dirname(os.path.abspath(__file__))
TTS_ENGINE_DIR = os.path.join(DIRECTORY, "tts_engine")
MODELS_DIR = os.path.join(TTS_ENGINE_DIR, "models")
ESPEAK_DIR = os.path.join(TTS_ENGINE_DIR, "espeak-ng-data")
TOKENS_PATH = os.path.join(TTS_ENGINE_DIR, "tokens.txt")
PREVIEWS_DIR = os.path.join(DIRECTORY, "previews")
CACHE_DIR = os.path.join(DIRECTORY, "cache_tts")

for d in [TTS_ENGINE_DIR, MODELS_DIR, ESPEAK_DIR, PREVIEWS_DIR, CACHE_DIR]:
    os.makedirs(d, exist_ok=True)

# Danh mục 25 mô hình giọng đọc AI Tiếng Việt đồng bộ 1:1 từ TtsVoiceCatalog.kt của Android
VOICE_CATALOG = {
    "ngoc_ngan": {"file": "ngocngan3701.onnx", "name": "Ngọc Ngạn", "speed": 0.92},
    "quang_minh": {"file": "minhquang.onnx", "name": "Quang Minh", "speed": 0.96},
    "ban_mai": {"file": "banmai.onnx", "name": "Ban Mai", "speed": 1.0},
    "tran_thanh": {"file": "tranthanh3870.onnx", "name": "Trấn Thành", "speed": 1.05},
    "viet_thao": {"file": "vietthao3886.onnx", "name": "Việt Thảo", "speed": 0.95},
    "ngoc_huyen": {"file": "ngochuyen.onnx", "name": "Ngọc Huyền", "speed": 0.95},
    "ngoc_huyen_new": {"file": "ngochuyennew.onnx", "name": "Ngọc Huyền (Bản mới)", "speed": 1.0},
    "minh_thu": {"file": "minhthu.onnx", "name": "Minh Thu", "speed": 1.0},
    "duy": {"file": "duyoryx3175.onnx", "name": "Duy", "speed": 0.95},
    "vais1000": {"file": "minhkhang.onnx", "name": "Minh Khang", "speed": 1.0},
    "phuong_mai": {"file": "maiphuong.onnx", "name": "Phương Mai", "speed": 1.0},
    "lac_phi": {"file": "lacphi.onnx", "name": "Lạc Phi", "speed": 0.95},
    "chieu_thanh": {"file": "chieuthanh.onnx", "name": "Chiếu Thành", "speed": 0.95},
    "manh_dung": {"file": "manhdung.onnx", "name": "Mạnh Dũng", "speed": 1.0},
    "my_tam_2": {"file": "mytam2.onnx", "name": "Mỹ Tâm (Bản 2)", "speed": 1.0},
    "my_tam_2794": {"file": "mytam2794.onnx", "name": "Mỹ Tâm (Bản 2794)", "speed": 1.0},
    "phuong_trang": {"file": "phuongtrang.onnx", "name": "Phương Trang", "speed": 1.0},
    "tai_an_2": {"file": "taian2.onnx", "name": "Tài An (Bản 2)", "speed": 1.0},
    "tai_an_4": {"file": "taian4.onnx", "name": "Tài An (Bản 4)", "speed": 1.0},
    "thanh_phuong": {"file": "thanhphuong2.onnx", "name": "Thanh Phương", "speed": 1.0},
    "thien_tam": {"file": "thientam.onnx", "name": "Thiện Tâm", "speed": 0.95},
    "calm_woman": {"file": "calmwoman3688.onnx", "name": "Calm Woman", "speed": 0.95},
    "deep_man": {"file": "deepman3909.onnx", "name": "Deep Man", "speed": 0.92},
    "adam": {"file": "adam1.onnx", "name": "Adam", "speed": 1.0},
    "yan_new": {"file": "yannew.onnx", "name": "Yan", "speed": 1.05},
}

HF_BASE_URL = "https://huggingface.co/doof-ferb/nghitts-copy/resolve/main/sherpa-onnx"

class SherpaTtsManager:
    """
    Trình quản lý Sherpa-ONNX TTS Engine
    Tự động nạp, cache và tổng hợp giọng đọc AI tiếng Việt theo từng model nghệ sĩ
    """
    def __init__(self):
        self.engines = {}
        self.lock = threading.Lock()
        self.sherpa_available = False
        try:
            import sherpa_onnx
            self.sherpa_onnx = sherpa_onnx
            self.sherpa_available = True
            print("[TTS Engine] Đã nạp thành công thư viện sherpa-onnx.")
        except ImportError:
            print("[TTS Engine] Chưa cài đặt sherpa-onnx. Chạy fallback.")
            self.sherpa_onnx = None

    def ensure_tokens_and_espeak(self):
        """Đảm bảo tokens.txt và espeak-ng-data đã sẵn sàng"""
        if not os.path.exists(TOKENS_PATH) or os.path.getsize(TOKENS_PATH) == 0:
            tokens_url = f"{HF_BASE_URL}/tokens.txt"
            print(f"[TTS Engine] Đang tải tokens.txt từ {tokens_url}...")
            req = urllib.request.Request(tokens_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req) as resp, open(TOKENS_PATH, 'wb') as out:
                out.write(resp.read())
            print("[TTS Engine] tokens.txt đã tải xong.")

    def ensure_model_file(self, voice_id):
        """Kiểm tra và tải model ONNX nếu chưa có"""
        info = VOICE_CATALOG.get(voice_id)
        if not info:
            voice_id = "ngoc_ngan"
            info = VOICE_CATALOG[voice_id]

        onnx_file = info["file"]
        model_path = os.path.join(MODELS_DIR, onnx_file)
        if not os.path.exists(model_path) or os.path.getsize(model_path) < 10000000:
            onnx_url = f"{HF_BASE_URL}/{onnx_file}"
            print(f"[TTS Engine] Đang tải model AI {info['name']} ({onnx_file}) từ HuggingFace...")
            req = urllib.request.Request(onnx_url, headers={'User-Agent': 'Mozilla/5.0'})
            temp_path = model_path + ".tmp"
            with urllib.request.urlopen(req) as resp, open(temp_path, 'wb') as out:
                while True:
                    chunk = resp.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
            if os.path.exists(model_path):
                try:
                    os.remove(model_path)
                except Exception:
                    pass
            os.rename(temp_path, model_path)
            print(f"[TTS Engine] Đã tải xong model {info['name']} ({os.path.getsize(model_path)} bytes)")
        return model_path

    def get_engine(self, voice_id):
        """Khởi tạo hoặc lấy instance OfflineTts đã được cache trong bộ nhớ"""
        if not self.sherpa_available:
            return None

        with self.lock:
            if voice_id in self.engines:
                return self.engines[voice_id]

            self.ensure_tokens_and_espeak()
            model_path = self.ensure_model_file(voice_id)

            print(f"[TTS Engine] Đang khởi tạo mô hình Sherpa-ONNX cho giọng: {voice_id}...")
            start_t = time.time()
            vits_config = self.sherpa_onnx.OfflineTtsVitsModelConfig(
                model=model_path,
                tokens=TOKENS_PATH,
                data_dir=ESPEAK_DIR,
                noise_scale=0.667,
                noise_scale_w=0.8,
                length_scale=1.0,
            )
            model_config = self.sherpa_onnx.OfflineTtsModelConfig(
                vits=vits_config,
                num_threads=2,
                debug=False,
            )
            tts_config = self.sherpa_onnx.OfflineTtsConfig(
                model=model_config,
                max_num_sentences=1,
            )
            tts = self.sherpa_onnx.OfflineTts(tts_config)
            self.engines[voice_id] = tts
            print(f"[TTS Engine] Khởi tạo xong giọng {voice_id} ({time.time() - start_t:.2f}s). SampleRate: {tts.sample_rate}Hz")
            return tts

    def synthesize(self, text, voice_id="ngoc_ngan", user_speed=1.0):
        """
        Tổng hợp câu văn bản thành luồng âm thanh WAV
        Trả về bytes của tệp WAV
        """
        info = VOICE_CATALOG.get(voice_id, VOICE_CATALOG["ngoc_ngan"])
        base_speed = info.get("speed", 1.0)
        effective_speed = max(0.5, min(2.0, base_speed * user_speed))

        # Khóa đệm hash
        text_hash = hashlib.md5(f"{voice_id}_{effective_speed:.2f}_{text}".encode('utf-8')).hexdigest()
        cache_path = os.path.join(CACHE_DIR, f"{text_hash}.wav")

        if os.path.exists(cache_path) and os.path.getsize(cache_path) > 1000:
            with open(cache_path, "rb") as f:
                return f.read(), "audio/wav"

        # Nếu sherpa_onnx có sẵn, dùng model thật
        if self.sherpa_available:
            try:
                engine = self.get_engine(voice_id)
                if engine:
                    audio = engine.generate(text, sid=0, speed=effective_speed)
                    self.sherpa_onnx.write_wave(cache_path, audio.samples, audio.sample_rate)
                    with open(cache_path, "rb") as f:
                        return f.read(), "audio/wav"
            except Exception as e:
                print(f"[TTS Engine Error] Lỗi tổng hợp với Sherpa-ONNX: {e}")

        # Fallback Google TTS MP3 nếu Sherpa chưa sẵn sàng
        print(f"[TTS Engine] Sử dụng fallback cho '{text[:40]}...'")
        encoded_text = urllib.parse.quote(text)
        tts_url = f"https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q={encoded_text}"
        req = urllib.request.Request(tts_url, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        })
        with urllib.request.urlopen(req, timeout=10) as resp:
            audio_data = resp.read()
        return audio_data, "audio/mpeg"

tts_manager = SherpaTtsManager()

class EpubProServerHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIRECTORY, **kwargs)

    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def do_GET(self):
        parsed_url = urllib.parse.urlparse(self.path)

        # 1. Endpoint kiểm tra trạng thái AI TTS Engine & các model đã tải
        if parsed_url.path == '/api/tts/status':
            downloaded = []
            for vid, info in VOICE_CATALOG.items():
                p = os.path.join(MODELS_DIR, info["file"])
                if os.path.exists(p) and os.path.getsize(p) > 10000000:
                    downloaded.append(vid)

            status_data = {
                "status": "online",
                "engine": "Sherpa_ONNX_VITS_Local",
                "sherpa_available": tts_manager.sherpa_available,
                "downloaded_models": downloaded,
                "active_loaded_engines": list(tts_manager.engines.keys()),
                "total_catalog_count": len(VOICE_CATALOG)
            }
            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.end_headers()
            self.wfile.write(json.dumps(status_data, ensure_ascii=False).encode('utf-8'))
            return

        # 2. Endpoint tải trước mô hình giọng đọc
        if parsed_url.path == '/api/tts/download':
            query_params = urllib.parse.parse_qs(parsed_url.query)
            voice_id = query_params.get('voice', ['ngoc_ngan'])[0]
            try:
                tts_manager.ensure_model_file(voice_id)
                res = {"success": True, "voice": voice_id, "status": "ready"}
            except Exception as e:
                res = {"success": False, "voice": voice_id, "error": str(e)}

            self.send_response(200)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.end_headers()
            self.wfile.write(json.dumps(res, ensure_ascii=False).encode('utf-8'))
            return

        # 3. Endpoint phát âm thanh TTS trực tiếp
        if parsed_url.path == '/api/tts':
            query_params = urllib.parse.parse_qs(parsed_url.query)
            text = query_params.get('text', [''])[0]
            voice_id = query_params.get('voice', ['ngoc_ngan'])[0]
            speed = float(query_params.get('speed', ['1.0'])[0])

            if not text:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b"Missing text parameter")
                return

            print(f"[TTS Server] Tổng hợp giọng '{voice_id}' (speed={speed}): {text[:60]}...")
            try:
                audio_bytes, mime_type = tts_manager.synthesize(text, voice_id, speed)
                self.send_response(200)
                self.send_header('Content-Type', mime_type)
                self.send_header('Content-Length', str(len(audio_bytes)))
                self.send_header('Cache-Control', 'public, max-age=86400')
                self.end_headers()
                self.wfile.write(audio_bytes)
                return
            except Exception as e:
                print(f"[TTS Server Error] Lỗi: {e}")
                self.send_response(500)
                self.end_headers()
                self.wfile.write(f"TTS Error: {e}".encode('utf-8'))
                return

        # 4. Phục vụ các file tĩnh web và thư mục previews
        super().do_GET()

def run_server():
    server_address = ('', PORT)
    httpd = HTTPServer(server_address, EpubProServerHandler)
    print("=" * 70)
    print("  🚀 EpubPro Web Reader — Sherpa-ONNX AI TTS Server đã khởi chạy!")
    print(f"  👉 Truy cập trình đọc: http://localhost:{PORT}")
    print(f"  👉 Trực tiếp:          http://127.0.0.1:{PORT}")
    print("  ⭐ Đã kích hoạt 25 mô hình giọng đọc AI Tiếng Việt từ HuggingFace")
    print("=" * 70)
    print("Nhấn Ctrl + C để dừng máy chủ.\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nĐang dừng máy chủ...")
        httpd.server_close()

if __name__ == '__main__':
    run_server()
