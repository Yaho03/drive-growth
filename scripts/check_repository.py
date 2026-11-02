"""저장소 필수 파일과 Markdown 로컬 링크의 누락을 검사한다."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "README.md", "AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md",
    "docs/01_PRD.md", "docs/02_SYSTEM_ARCHITECTURE.md", "docs/06_VERIFICATION.md",
    ".github/PULL_REQUEST_TEMPLATE.md", ".github/ISSUE_TEMPLATE/feature.md",
    ".github/ISSUE_TEMPLATE/bug.md", ".github/workflows/ci.yml",
    "gradlew", "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties", "settings.gradle.kts",
    "app/build.gradle.kts", "core/build.gradle.kts",
)


def main():
    errors = [f"필수 파일 없음: {name}" for name in REQUIRED if not (ROOT / name).is_file()]
    documents = list(ROOT.glob("*.md")) + list((ROOT / "docs").rglob("*.md"))
    checked = 0
    for document in documents:
        # 코드 예제 안의 링크 문법은 검사 대상에서 제외한다.
        content = re.sub(r"```.*?```", "", document.read_text(encoding="utf-8"), flags=re.S)
        for match in re.finditer(r"\]\((<[^>]+>|[^\s)]+)(?:\s+\"[^\"]*\")?\)", content):
            target = match.group(1).strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            checked += 1
            if not (document.parent / unquote(parsed.path)).exists():
                errors.append(f"끊어진 링크: {document.relative_to(ROOT)} → {target}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"저장소 검사 통과: 필수 파일 {len(REQUIRED)}개, 문서 {len(documents)}개, 로컬 링크 {checked}개")
    return 0


if __name__ == "__main__":
    sys.exit(main())
