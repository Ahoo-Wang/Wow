#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Finding:
    path: Path
    line: int
    message: str


PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"\bUse\s+(?:Grep|Glob)\b|\b(?:Grep|Glob)\s+to\b"),
        "Use rg or rg --files instead of Claude-style Grep/Glob wording.",
    ),
    (
        re.compile(r"(?:^|[\s`./])(?:domain|api):(?:compileKotlin|test|check|jacocoTestReport|jacocoTestCoverageVerification)\b"),
        "Use resolved Gradle module placeholders instead of hard-coded domain/api modules.",
    ),
    (
        re.compile(r"\bwhere\s*\{|\bpage\s*\("),
        "Use Wow Query DSL condition/pagination APIs instead of where/page wording.",
    ),
    (
        re.compile(r"\bcountQuery\b"),
        "Use `Condition.count(queryService)` wording; Wow does not expose a countQuery DSL function.",
    ),
    (
        re.compile(r"\bCommand-Wait-Timeout\b"),
        "Use the current source header spelling `Command-Wait-Timout`, and note the docs/source mismatch when relevant.",
    ),
    (
        re.compile(r"\bPreparedValue\s*\("),
        "Use `value.toForever()` or `value.toTtlAt(ttlAt)`; PreparedValue is an interface, not a Duration constructor.",
    ),
    (
        re.compile(r"@Enabled\s*\("),
        "Do not document a generic `@Enabled` annotation unless it exists in the current Wow checkout.",
    ),
    (
        re.compile(r"@get:(?:Summary|Description)\b"),
        "Use property-level `@Summary`/`@Description`; current annotations do not target property getters.",
    ),
    (
        re.compile(r"\bwow\.compensation\.(?:host|webhook)|\bwebhook\.weixin\b"),
        "Do not include deployment-only compensation properties in business-service skills; use Saga/Event handler `@Retry` guidance instead.",
    ),
    (
        re.compile(r"\bassertThat\s*\("),
        "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
    ),
    (
        re.compile(r"\b(?:TODO|TBD|FIXME)\b"),
        "Resolve placeholders before shipping skill content.",
    ),
    (
        re.compile(r"\bcom\.xxx\b"),
        "Use realistic package placeholders or fully qualified names from the target module.",
    ),
    (
        re.compile(r"\bProductCostTestCases\b"),
        "Do not reference non-existent project-specific examples.",
    ),
)

LOCAL_MARKDOWN_REF = re.compile(r"`((?:references/|\.\.?/)[^`]+\.md)`|\]\(((?:references/|\.\.?/)[^)]+\.md)\)")


def iter_skill_files(root: Path) -> list[Path]:
    skills_dir = root / "skills"
    if not skills_dir.exists():
        return []
    return sorted(path for path in skills_dir.rglob("*") if path.suffix in {".md", ".json"})


def lint(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in iter_skill_files(root):
        if path.suffix == ".json":
            try:
                json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                findings.append(Finding(path.relative_to(root), 1, "Invalid JSON file."))
            continue
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            for pattern, message in PATTERNS:
                if "assertThat" in line and re.search(r"\b(?:not|NOT|never|avoid)\b|不要|不使用", line):
                    continue
                if pattern.search(line):
                    findings.append(Finding(path.relative_to(root), line_no, message))
            for match in LOCAL_MARKDOWN_REF.finditer(line):
                raw_ref = match.group(1) or match.group(2)
                ref_path = (path.parent / raw_ref).resolve()
                if not ref_path.exists():
                    findings.append(
                        Finding(
                            path.relative_to(root),
                            line_no,
                            f"Referenced local markdown file does not exist: {raw_ref}",
                        )
                    )
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Lint Wow skill documentation for common drift patterns.")
    parser.add_argument("root", nargs="?", default=".", help="Repository root. Defaults to current directory.")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    findings = lint(root)
    for finding in findings:
        print(f"{finding.path}:{finding.line}: {finding.message}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
