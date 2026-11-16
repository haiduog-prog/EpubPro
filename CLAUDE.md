# CLAUDE.md

Toàn bộ quy định của project nằm trong `AGENTS.md` (dùng chung cho Codex và Claude Code). File này import lại để Claude Code đọc cùng một nguồn, tránh lệch nội dung giữa hai tool.

@AGENTS.md

---

## Ghi chú riêng cho Claude Code

- **Skills**: các skill được nhắc trong `AGENTS.md` (`android-compose-router-vi`, `senior-android`, `android-code-review`, `cb-*`, `compose-perf-*`, ...) nằm tại `.claude/skills/<tên-skill>/SKILL.md`. Gọi bằng Skill tool hoặc `/<tên-skill>`.
- **Rules**: các rule chi tiết nằm tại `.claude/rules/`:
  - `function-documentation.md` — quy tắc KDoc/Javadoc tiếng Việt.
  - `string-resources.md` — quy tắc không hardcode chuỗi UI.
  - `load-learnings.md` — cách dùng kho learnings.
  - `web-design-backbone.md` — chỉ áp dụng cho file web (`web-reader/**`).
- **Learnings**: kho kiến thức theo feature nằm tại `.agents/learnings/` (dùng chung với Codex). Hook `SessionStart` tự liệt kê tên file learnings vào context ở đầu mỗi session; chỉ đọc file liên quan đến task hiện tại.
- **Module instructions**: các module `core/epub`, `core/playback`, `core/reader-renderer`, `feature/reader` có `CLAUDE.md` riêng (import `AGENTS.md` của module), được nạp khi làm việc với file trong module đó.
- **Build/test trên Windows**: dùng `.\gradlew.bat <task>` (PowerShell) hoặc `./gradlew <task>` (Git Bash).

## Quy trình bổ sung cho Claude Code

Bổ sung cho phần `Development Workflow` trong `AGENTS.md`, áp dụng trước khi bắt đầu viết code.

- **Chức năng chưa quen thuộc**: đừng viết từ con số 0. Trước khi bắt đầu, tìm các dự án mã nguồn mở trên GitHub làm chức năng tương tự (ví dụ `gh search repos`, `gh search code`) để so sánh ý tưởng, cách tổ chức và các trường hợp biên. Báo người dùng ngắn gọn đã tham khảo repo nào và chọn hướng nào, vì sao. Chỉ chép code khi giấy phép cho phép (MIT, Apache-2.0...) và ghi nguồn trong comment; không chép code GPL vào dự án.
