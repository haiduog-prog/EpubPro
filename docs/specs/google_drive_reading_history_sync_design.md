# Thiết kế đồng bộ lịch sử đọc qua Google Drive

## Tóm tắt hiểu biết

- Đồng bộ vị trí đọc mới nhất theo từng truyện: CFI, chương, trang, phần trăm, số chương và thời điểm đọc gần nhất.
- Dùng cùng tài khoản Google để chuyển tiến độ giữa các thiết bị; xung đột được giải quyết theo lần cập nhật tiến độ mới nhất.
- Không đồng bộ nhật ký từng phiên, bookmark, highlight, nội dung ebook hay metadata thư viện trong tính năng này.
- Dữ liệu tiến độ được tách khỏi Room snapshot và file ebook để không thay database hoặc đường dẫn local của thiết bị.
- Người dùng chủ động chạy đồng bộ ở màn hình Google Drive hiện có.

## Giả định và ràng buộc

- Đồng hồ thiết bị được đồng bộ tương đối chính xác; timestamp epoch milliseconds là tiêu chí so sánh.
- Quy mô mục tiêu là thư viện cá nhân; ledger theo thiết bị có kích thước nhỏ và được tải trong một phiên sync.
- Drive lưu khóa truyện, vị trí đọc và mốc thời gian; không lưu access token hoặc nội dung đọc.
- Truyện online dùng `onlineNovelId`; sách local dùng SHA-256 file EPUB. Fingerprint local được cache theo book ID, đường dẫn canonical, độ dài và thời gian sửa file, rồi tính lại khi file thay đổi.
- Chỉ tự động áp dụng tiến độ nếu thiết bị có sách trùng khóa ổn định; giữ bản ghi unmatched trong ledger để thiết bị khác không làm mất.
- Kiểm thử thiết bị thật cần OAuth Android client khớp package name và SHA-1 của APK release.

## Nhật ký quyết định

| Quyết định | Phương án khác | Lý do |
|---|---|---|
| Tạo ledger tiến độ riêng trên Drive, tách khỏi SQLite snapshot. | Đồng bộ toàn bộ database; thêm backend máy chủ. | Tránh ghi đè metadata/đường dẫn local và tránh mở rộng backend. |
| Mỗi thiết bị ghi một ledger JSON của riêng mình; đọc và gộp tất cả ledger. | Mọi thiết bị ghi chung một file. | Tránh hai thiết bị ghi đè thay đổi của nhau khi sync gần đồng thời. |
| Gộp theo `updatedAt` của progress; nếu timestamp bằng nhau thì so `deviceId` ổn định. | Ghi đè theo thứ tự upload hoặc giữ local cố định. | Kết quả xác định và ưu tiên lần lưu tiến độ gần nhất. |
| Dùng `onlineNovelId` hoặc SHA-256 ebook làm khóa truyện. | Dùng ID import hiện tại hoặc tiêu đề/tác giả. | ID import ngẫu nhiên; tiêu đề/tác giả có thể trùng. |
| Sync thủ công từ màn hình Drive. | Đồng bộ nền sau mỗi thao tác đọc. | Giữ hành vi dễ hiểu, tiết kiệm mạng và tránh yêu cầu lịch nền mới. |
| Chỉ đồng bộ vị trí đọc và thời gian gần nhất. | Đồng bộ bookmark/highlight và nhật ký phiên. | Khớp phạm vi đã xác nhận, giảm dữ liệu và migration ngoài yêu cầu. |

## Thiết kế cuối cùng

### Dữ liệu và định danh

- Nâng `AppDatabase` từ version 7 lên 8.
- Thêm `ReadingProgress.updatedAt`; migration backfill giá trị cũ bằng `books.lastReadAt`.
- Thêm cache fingerprint của sách local cùng file length/lastModified để tránh băm file ở mỗi lần sync.
- Mỗi ledger gồm `schemaVersion`, `deviceId` và danh sách bản ghi: `bookKey`, `currentCfi`, `chapterIndex`, `pageIndex`, `progressPercentage`, `totalChapters`, `lastReadAt`, `updatedAt`.
- `deviceId` là UUID sinh một lần và lưu trong DataStore.

### Luồng đồng bộ

1. Nút `Đồng bộ lịch sử đọc` gọi thao tác riêng trên `SyncCoordinator`; mutex hiện có đảm bảo không chạy đồng thời với backup/restore.
2. Đọc tiến độ Room, sách và fingerprint; đọc tất cả ledger trong thư mục lịch sử đọc của `EpubBackendSync`.
3. Validate schema, khóa và phạm vi số liệu. Gộp theo `bookKey`, ưu tiên `updatedAt` lớn nhất; khi bằng nhau, dùng thứ tự `deviceId` cố định.
4. Trong Room transaction, áp dụng tiến độ thắng cho sách local có `bookKey` tương ứng và cập nhật `lastReadAt`. Bản ghi không có sách local vẫn được giữ lại.
5. Ghi ledger đã gộp vào file của thiết bị hiện tại. Lần sync sau trên các thiết bị khác sẽ nhận được union.

### Lỗi, quyền riêng tư và trường hợp biên

- Lỗi xác thực, mạng, checksum, schema hoặc JSON không hợp lệ không được làm thay đổi dữ liệu local; báo trạng thái và cho phép retry.
- Unknown book records được bảo toàn; nội dung file đổi làm fingerprint đổi và tránh áp dụng vị trí có thể không còn hợp lệ.
- Không ghi token vào DataStore/Drive/log; chỉ dùng Drive scope hiện có `drive.file`.
- Nếu đồng hồ thiết bị sai đáng kể, timestamp có thể chọn nhầm tiến độ; đây là giới hạn đã chấp nhận cho phiên bản đầu.

### Kiểm thử và xác nhận

- Unit test merger: timestamp mới hơn, timestamp bằng nhau, bản ghi unmatched, nhiều thiết bị và cập nhật lặp.
- Test codec: schema hợp lệ, schema không hỗ trợ, dữ liệu sai kiểu/ngoài miền.
- Test Room migration 7→8 và transaction cập nhật progress cùng `lastReadAt`.
- Test coordinator: nhiều ledger, remote mới hơn/local mới hơn, lỗi Drive không sửa local.
- Chạy test các module `domain`, `core:database`, `core:storage`, `feature:profile` và build app.
- Thử nghiệm end-to-end trên thiết bị sau khi Google Cloud Console có Android OAuth client khớp package name + SHA-1 release.
