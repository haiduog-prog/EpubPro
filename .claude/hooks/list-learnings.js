#!/usr/bin/env node
// Hook SessionStart: in danh sách tên file trong .agents/learnings/ ra stdout
// để Claude Code đưa vào context. Chỉ liệt kê tên file, không đọc nội dung.
const fs = require("fs");
const path = require("path");

const projectDir = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const learningsDir = path.join(projectDir, ".agents", "learnings");

/**
 * Lấy danh sách file Markdown trong thư mục learnings, sắp xếp theo tên.
 *
 * @param dir Đường dẫn tuyệt đối tới thư mục learnings.
 * @return Mảng tên file `.md`, hoặc mảng rỗng nếu thư mục không tồn tại/không đọc được.
 */
function listLearningFiles(dir) {
  try {
    return fs
      .readdirSync(dir)
      .filter((name) => name.toLowerCase().endsWith(".md"))
      .sort();
  } catch {
    return [];
  }
}

const files = listLearningFiles(learningsDir);
if (files.length > 0) {
  process.stdout.write(
    [
      "Learnings có sẵn tại .agents/learnings/ (chỉ đọc file liên quan đến task, theo .claude/rules/load-learnings.md):",
      ...files.map((name) => `- ${name}`),
    ].join("\n") + "\n"
  );
}
process.exit(0);
