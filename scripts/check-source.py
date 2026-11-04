from pathlib import Path
import sys

root = Path(__file__).resolve().parent.parent
excluded = {"node_modules", "target", "dist", "data", "work", ".git", "static", ".cache", "artifacts", "bin", "backups", "native-io"}
extensions = {".java", ".ts", ".tsx", ".css", ".py", ".sh", ".go"}
code, total = [], 0
for path in root.rglob("*"):
    if not path.is_file() or any(part in excluded for part in path.relative_to(root).parts):
        continue
    if path.name == ".env" or path.name.endswith((".log", ".tsbuildinfo")):
        continue
    total += path.stat().st_size
    if path.suffix in extensions:
        code.append((len(path.read_text().splitlines()), path.relative_to(root)))
violations = [(lines, path) for lines, path in code if lines > 500]
print(f"Source and configuration: {total / 1024 / 1024:.2f} MiB; code files: {len(code)}")
for lines, path in sorted(code, reverse=True)[:5]:
    print(f"{lines:4} lines  {path}")
if total > 20 * 1024 * 1024 or violations:
    print("Source size or 500-line limit exceeded", file=sys.stderr)
    sys.exit(1)
