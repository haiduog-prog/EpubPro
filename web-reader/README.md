# EpubPro Web Reader (Bản Đọc Web Cao Cấp)

> Phiên bản Web Reader độc lập của hệ thống **EpubPro**, kế thừa trọn vẹn triết lý thiết kế và trải nghiệm đọc sách số cao cấp từ ứng dụng Android.

---

## 🌟 Tính Năng Nổi Bật

1. **Kệ Sách Cá Nhân & Quản Lý Lịch Sử Đọc (Reading History)**:
   - Toàn bộ truyện demo mẫu đã được gỡ bỏ; bạn hoàn toàn làm chủ kệ sách cá nhân của mình.
   - **Lưu trữ bền vững IndexedDB**: Lưu trữ toàn bộ nội dung tệp EPUB và dữ liệu sách an toàn ngay trên trình duyệt mà không bị giới hạn dung lượng 5MB của localStorage.
   - **Theo dõi tiến độ & Lịch sử đọc chuẩn Android (`ReadingHistoryRecord`)**:
     - Ghi nhận chính xác mốc thời gian đọc gần nhất ("Vừa xong", "15 phút trước", ngày giờ cụ thể).
     - Lưu chi tiết chương đang đọc dở, vị trí trang và tỷ lệ hoàn thành (`%`).
     - Bộ lọc tiện lợi: *Tất cả sách*, *⏳ Lịch sử đọc*, *📖 Đang đọc dở*, *✅ Đã hoàn thành*.
     - Nút "Đọc tiếp ›" giúp bạn lập tức quay lại đúng trang và chương dở dang.
     - Hỗ trợ xóa từng cuốn hoặc xóa toàn bộ lịch sử đọc khi cần.

2. **Phân Tích File EPUB Client-side Trực Tiếp**:
   - Tự động giải nén và phân tích cấu trúc EPUB chuẩn (`container.xml`, `content.opf`, manifest, spine, NCX / Nav doc).
   - Hỗ trợ tải ảnh nhúng bên trong file zip chuyển sang `blob:` URL xem offline an toàn.
   - Kéo & thả (Drag and drop) file `.epub` hoặc chọn tệp trực tiếp từ máy tính để thêm vĩnh viễn vào Kệ Sách.

3. **Hai Chế Độ Đọc Chuyên Nghiệp**:
   - **Lật Trang Ngang (Paged Multi-column)**: Tái hiện trang sách giấy kinh điển bằng công nghệ CSS Multi-Column Pagination, chống tràn và hiển thị số trang trực quan.
   - **Cuộn Dọc Vô Tận (Infinite Scroll)**: Đọc liền mạch, tự nhiên, thích hợp cho màn hình di động hoặc tài liệu dài.

4. **5 Bộ Giao Diện Công Thái Học Thị Giác**:
   - 📜 **Sepia (Cổ điển)**: Tông màu ấm dịu mắt, giả lập trang giấy ngả vàng.
   - 📑 **Giấy mộc (Paper)**: Tông màu thanh lịch, phong cách sách in cao cấp.
   - 🌞 **Sáng (Light)**: Chuẩn mực, rõ ràng, tương phản cao.
   - 🌙 **Tối (Dark)**: Tông xám than dịu nhẹ khi đọc ban đêm.
   - 🌌 **Nửa đêm (Midnight)**: Tông đen OLED sâu thẳm, tiết kiệm pin tối đa.

5. **Tùy Biến Trình Bày Sâu (Typography & Spacing)**:
   - Các phông chữ tuyển chọn chuẩn biên tập: *Literata*, *Cormorant Garamond*, *Plus Jakarta Sans*.
   - Tùy chỉnh cỡ chữ (14px – 28px), độ giãn dòng (1.4 – 2.4), khoảng cách lề và chế độ căn dòng (Căn đều 2 bên / Căn trái).

6. **Trình Phát Audio TTS & Kho Giọng Đọc AI Đồng Bộ Chuẩn App Android**:
   - **TtsVoiceCatalog (25 Giọng Đọc AI Thuần Việt Tải Về Offline)**:
     - Đồng bộ 100% từ danh mục `TtsVoiceCatalog.kt` và `VoiceModelDownloader.kt` của ứng dụng Android EpubPro.
     - Tải trực tiếp các mô hình AI ONNX từ kho HuggingFace (`doof-ferb/nghitts-copy`): *Ngọc Ngạn, Quang Minh, Ban Mai, Trấn Thành, Việt Thảo, Ngọc Huyền, Minh Thu, Duy, Minh Khang, v.v.*
     - **Quản lý tải về & Lưu trữ IndexedDB**: Hỗ trợ tải mô hình về lưu trữ vĩnh viễn trên trình duyệt, có thanh tiến trình % trực quan, nút nghe thử mẫu giọng và nút xóa giải phóng bộ nhớ.
     - **Loại bỏ hoàn toàn giọng tiếng Anh hệ thống**: Tuyệt đối không đưa các giọng mặc định như `Microsoft David - En` hay `Zira` vào danh sách chọn giọng tiếng Việt.
   - **TtsMiniPlayerBar**: Thanh phát mini nổi ở cạnh dưới màn hình, hiển thị trạng thái "ĐANG PHÁT • [Tên Giọng AI]", câu/đoạn trích dẫn đang đọc và nút mở rộng nhanh.
   - **TtsAudioPlayerScreen**: Màn hình trình phát âm thanh toàn diện phong cách Stitch UI:
     - Thẻ thông tin sách (Book Card): Tiêu đề, tác giả, chương hiện tại.
     - Thẻ đoạn văn (Paragraph Card): Trích xuất và đọc từng đoạn (`TtsChunk`), hiển thị **Soundwave Audio Visualizer** nhảy sóng theo thời gian thực.
     - **Tô màu Highlight đồng bộ (`.tts-active-paragraph`)**: Tự động tô sáng đoạn văn đang phát và tự động lật trang (Paged mode) hoặc cuộn mượt (Scroll mode) theo giọng đọc.
     - Tua thời gian & đoạn văn: Nút lùi đoạn (`SkipPrevious`), nút tới đoạn (`SkipNext`), thanh trượt Timeline kéo thả đến đoạn bất kỳ.
     - Cài đặt âm thanh nâng cao: Tốc độ đọc (0.75x – 2.0x), bộ chọn **Giọng AI Tiếng Việt** kèm nút mở nhanh **Kho Giọng AI**, và **Hẹn giờ tắt (Sleep Timer)** (Tắt, 15p, 30p, 45p, Hết chương).
   - Tự động chuyển chương khi đọc hết đoạn cuối của chương hiện tại.

7. **Tiện Ích & Điều Hướng Hiện Đại**:
   - Mục lục (Table of Contents) nhảy nhanh đến chương bất kỳ.
   - Đánh dấu trang (Bookmarks) lưu cục bộ theo chương & vị trí.
   - Thanh tiến độ đọc trực quan, tự động nhớ vị trí đọc dở qua `localStorage`.
   - Phím tắt bàn phím:
     - `L`: Mở / đóng Kho Sách & Kệ Truyện
     - `←` / `→` hoặc `Space`: Lật trang
     - `T`: Đổi giao diện màu nhanh
     - `F`: Bật / tắt chế độ toàn màn hình (Fullscreen)
     - `M`: Mở ngăn kéo mục lục chương (TOC)
     - `Esc`: Đóng các cửa sổ / ngăn kéo

---

## 🚀 Hướng Dẫn Sử Dụng

### Cách 1: Mở Trực Tiếp (Không Cần Cài Đặt Server)
Bạn có thể nhấp đúp trực tiếp vào tệp `web-reader/index.html` để mở trên bất kỳ trình duyệt nào (Chrome, Edge, Firefox, Safari).

### Cách 2: Chạy Qua Máy Chủ Web Cục Bộ & Local TTS Server (Khuyên Dùng)
Mở terminal tại thư mục `web-reader/` và chạy lệnh máy chủ đi kèm:

**Chạy Local AI Server:**
```bash
python server.py
```
Sau đó truy cập: [http://localhost:8080](http://localhost:8080)

*Máy chủ `server.py` tích hợp sẵn bộ đệm âm thanh MP3 cục bộ (cache), hỗ trợ streaming giọng đọc tiếng Việt siêu mượt mà không bị gián đoạn hay phụ thuộc gói ngôn ngữ của Windows.*

---

## 📁 Cấu Trúc Mã Nguồn

```text
web-reader/
├── index.html            # Giao diện chính Semantic HTML5
├── README.md             # Tài liệu hướng dẫn sử dụng
├── css/
│   └── reader.css        # Hệ thống CSS Tokens, Typography, Themes, Animations, Modal Kho Giọng AI
└── js/
    ├── sample-book.js    # Dữ liệu sách mẫu / khởi tạo
    ├── epub-parser.js    # Bộ bóc tách tệp EPUB (JSZip) client-side
    ├── voice-catalog.js  # Danh mục 25 giọng AI tiếng Việt & Quản lý tải IndexedDB
    └── reader.js         # Engine điều khiển đọc, lật trang, TTS, lưu tiến độ
```
