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


ASSERT_THAT_PATTERN = re.compile(r"\bassertThat\s*\(")
NEGATIVE_ASSERT_THAT_GUIDANCE = re.compile(r"\b(?:not|NOT|never|avoid)\b|不要|不使用")
LEGACY_WAIT_TIMEOUT_PATTERN = re.compile(r"\bCommand-Wait-Timout\b")
LEGACY_WAIT_TIMEOUT_GUIDANCE = re.compile(r"\blegacy\b.*\bcompatibility\b|\bcompatibility\b.*\blegacy\b")

PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"\bUse\s+(?:Grep|Glob)\b|\b(?:Grep|Glob)\s+to\b"),
        "Use rg or rg --files instead of Claude-style Grep/Glob wording.",
    ),
    (
        re.compile(r"(?:^|[\s`./:])(?:domain|api):(?:compileKotlin|test|check|jacocoTestReport|jacocoTestCoverageVerification)\b"),
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
        LEGACY_WAIT_TIMEOUT_PATTERN,
        "Use the documented `Command-Wait-Timeout` header; the misspelled form is legacy compatibility only.",
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
        ASSERT_THAT_PATTERN,
        "Use FluentAssert `.assert()` instead of AssertJ `assertThat()`.",
    ),
    (
        re.compile(r"\b(?:TODO|TBD|FIXME)\b"),
        "Resolve placeholders before shipping skill content.",
    ),
    (
        re.compile(r"\*\*/settings\.gradle\.kts\b"),
        "Use rg-native `-g \"settings.gradle.kts\"` filtering instead of shell globstar.",
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
FRONTMATTER_KEY = re.compile(r"^([A-Za-z0-9_-]+):(?: +(.*))?$")
FRONTMATTER_KEY_WITHOUT_SEPARATOR = re.compile(r"^([A-Za-z0-9_-]+):(\S.*)$")
SKILL_NAME = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
ALLOWED_SKILL_FRONTMATTER_KEYS = frozenset({"name", "description"})
YAML_BLOCK_SCALARS = frozenset({"|", "|-", "|+", ">", ">-", ">+"})
YAML_NON_STRING_SCALAR = re.compile(
    r"(?:true|false|yes|no|on|off|null|~|[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)",
    re.IGNORECASE,
)
FrontmatterField = tuple[str, int, bool]


def parse_double_quoted_yaml_string(value: str) -> tuple[str, bool]:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return "", False
    return (parsed, True) if isinstance(parsed, str) else ("", False)


def parse_single_quoted_yaml_string(value: str) -> tuple[str, bool]:
    if len(value) < 2 or not value.endswith("'"):
        return "", False
    return value[1:-1].replace("''", "'"), True


def is_invalid_plain_yaml_string(value: str) -> bool:
    return bool(
        value[0] in "[{&*!>|,]}%@`"
        or value.startswith(("- ", "? ", "#"))
        or ": " in value
        or YAML_NON_STRING_SCALAR.fullmatch(value)
    )


def parse_yaml_string(value: str) -> tuple[str, bool]:
    if value in YAML_BLOCK_SCALARS:
        return "", True
    if not value:
        return "", False
    if value.startswith('"'):
        return parse_double_quoted_yaml_string(value)
    if value.startswith("'"):
        return parse_single_quoted_yaml_string(value)
    if is_invalid_plain_yaml_string(value):
        return "", False
    return value, True


def lint_frontmatter_indentation(
    relative_path: Path,
    line_no: int,
    line: str,
    active_block_key: str | None,
) -> Finding | None:
    indent = line[: len(line) - len(line.lstrip())]
    if "\t" in indent:
        return Finding(
            relative_path,
            line_no,
            "SKILL.md frontmatter must use spaces, not tabs, for indentation.",
        )
    if active_block_key is None:
        return Finding(
            relative_path,
            line_no,
            "SKILL.md frontmatter contains unexpected indented content.",
        )
    return None


def parse_frontmatter_mapping(
    relative_path: Path,
    line_no: int,
    line: str,
    keys: dict[str, FrontmatterField],
    findings: list[Finding],
) -> str | None:
    match = FRONTMATTER_KEY.match(line)
    if match is None:
        return parse_invalid_frontmatter_mapping(relative_path, line_no, line, keys, findings)

    key = match.group(1)
    raw_value = (match.group(2) or "").strip()
    value, is_string = parse_yaml_string(raw_value)
    keys[key] = (value, line_no, is_string)
    if not is_string:
        findings.append(
            Finding(
                relative_path,
                line_no,
                f"SKILL.md frontmatter field must be a YAML string: {key}",
            )
        )
    if key not in ALLOWED_SKILL_FRONTMATTER_KEYS:
        findings.append(
            Finding(
                relative_path,
                line_no,
                f"SKILL.md frontmatter contains unsupported key: {key}",
            )
        )
    return key if raw_value in YAML_BLOCK_SCALARS else None


def parse_invalid_frontmatter_mapping(
    relative_path: Path,
    line_no: int,
    line: str,
    keys: dict[str, FrontmatterField],
    findings: list[Finding],
) -> None:
    missing_separator_match = FRONTMATTER_KEY_WITHOUT_SEPARATOR.match(line)
    if missing_separator_match is not None:
        key = missing_separator_match.group(1)
        keys[key] = ("", line_no, False)
        findings.append(
            Finding(
                relative_path,
                line_no,
                "SKILL.md frontmatter mapping values require whitespace after `:`.",
            )
        )
        return None
    findings.append(
        Finding(
            relative_path,
            line_no,
            "SKILL.md frontmatter contains invalid YAML syntax.",
        )
    )
    return None


def lint_frontmatter_lines(
    relative_path: Path,
    lines: list[str],
) -> tuple[dict[str, FrontmatterField], list[Finding]]:
    keys: dict[str, FrontmatterField] = {}
    findings: list[Finding] = []
    active_block_key: str | None = None
    for line_no, line in enumerate(lines, start=2):
        if not line.strip():
            continue
        if line[0].isspace():
            finding = lint_frontmatter_indentation(relative_path, line_no, line, active_block_key)
            if finding is not None:
                findings.append(finding)
            continue
        active_block_key = parse_frontmatter_mapping(relative_path, line_no, line, keys, findings)
    return keys, findings


def lint_required_frontmatter_keys(relative_path: Path, keys: dict[str, FrontmatterField]) -> list[Finding]:
    return [
        Finding(
            relative_path,
            1,
            f"SKILL.md frontmatter is missing required key: {required_key}",
        )
        for required_key in sorted(ALLOWED_SKILL_FRONTMATTER_KEYS - keys.keys())
    ]


def lint_skill_name(path: Path, relative_path: Path, keys: dict[str, FrontmatterField]) -> list[Finding]:
    if "name" not in keys:
        return []
    name, line_no, is_string = keys["name"]
    if not is_string:
        return []
    if not SKILL_NAME.fullmatch(name) or len(name) > 64:
        return [
            Finding(
                relative_path,
                line_no,
                "SKILL.md name must use 1-64 lowercase letters, digits, or hyphens.",
            )
        ]
    if name != path.parent.name:
        return [
            Finding(
                relative_path,
                line_no,
                f"SKILL.md name must match its parent directory: {path.parent.name}",
            )
        ]
    return []


def lint_skill_frontmatter(root: Path, path: Path, text: str) -> list[Finding]:
    relative_path = path.relative_to(root)
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        return [Finding(relative_path, 1, "SKILL.md must start with YAML frontmatter.")]

    try:
        closing_line = lines.index("---", 1)
    except ValueError:
        return [Finding(relative_path, 1, "SKILL.md frontmatter is missing its closing delimiter.")]

    keys, findings = lint_frontmatter_lines(relative_path, lines[1:closing_line])
    findings.extend(lint_required_frontmatter_keys(relative_path, keys))
    findings.extend(lint_skill_name(path, relative_path, keys))
    return findings


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
        lines = text.splitlines()
        if path.name == "SKILL.md":
            findings.extend(lint_skill_frontmatter(root, path, text))
        if path.parent.name == "references" and len(lines) > 100 and "## Contents" not in lines[:40]:
            findings.append(
                Finding(
                    path.relative_to(root),
                    1,
                    "Reference files longer than 100 lines must include `## Contents` near the top.",
                )
            )
        for line_no, line in enumerate(lines, start=1):
            for pattern, message in PATTERNS:
                if (
                    pattern is ASSERT_THAT_PATTERN
                    and ASSERT_THAT_PATTERN.search(line)
                    and NEGATIVE_ASSERT_THAT_GUIDANCE.search(line)
                ):
                    continue
                if (
                    pattern is LEGACY_WAIT_TIMEOUT_PATTERN
                    and LEGACY_WAIT_TIMEOUT_PATTERN.search(line)
                    and LEGACY_WAIT_TIMEOUT_GUIDANCE.search(line)
                ):
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
