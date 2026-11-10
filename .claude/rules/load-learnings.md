# Load Learnings Rule

Kho learnings theo feature nằm tại `.agents/learnings/` (dùng chung với Codex).

## Cơ chế trên Claude Code

Hook `SessionStart` (`.claude/hooks/list-learnings.js`, khai báo trong `.claude/settings.json`) tự đưa **danh sách tên file** learnings vào context ở đầu mỗi session. Không cần tự liệt kê lại thư mục.

## Hành vi bắt buộc

1. Dựa vào danh sách tên file, xác định file nào liên quan đến task hiện tại.
   - Ví dụ: task về TTS → đọc `tts-engine.md`; task về widget → đọc `widget.md`.
   - Task liên quan nhiều feature → đọc nhiều file tương ứng.
   - Không có file liên quan → bỏ qua.
2. **Chỉ đọc nội dung** file liên quan — KHÔNG đọc tất cả để tiết kiệm context.
3. Báo ngắn cho user tên file đã đọc:
   > "Đã đọc 2 file learnings: `tts-engine.md`, `profile-settings.md`"

   Nếu không có file liên quan:
   > "Không có file learnings liên quan đến task này."
4. Áp dụng ngầm các learnings đã đọc — không hỏi lại những gì đã biết.

## Quy tắc

- File không tồn tại hoặc rỗng → bỏ qua, không báo lỗi.
- Không đọc lại file đã đọc trong cùng session.
