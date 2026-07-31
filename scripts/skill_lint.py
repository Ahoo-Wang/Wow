#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Iterator
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
MARKDOWN_FENCE_PATTERN = re.compile(r"^ {0,3}(?P<fence>`{3,}|~{3,})(?P<info>.*)$")

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
    (
        re.compile(r"\bexpect(?:Error|Event|Command)Type<[^>]+>\(\)|\bexpectEventType\s*\{"),
        "Use the actual type assertion APIs with a KClass/Class argument.",
    ),
    (
        re.compile(r"All DSL functions are in package `me\.ahoo\.wow\.query\.dsl`"),
        "Distinguish query-builder DSL packages from backend-specific query execution extensions.",
    ),
    (
        re.compile(r"falls back to lowercased class name|Space-separate the resource name"),
        "Describe AggregateRoute defaults and spaced behavior from the current OpenAPI implementation.",
    ),
    (
        re.compile(r"(?<![A-Za-z0-9_])CompensationFilter\b"),
        "Use the concrete `EventCompensationFilter` type or describe the runtime boundary generically.",
    ),
    (
        re.compile(r"Commands and domain events should include Wow API metadata annotations"),
        "Require API metadata only when commands or events are part of the API/domain contract.",
    ),
    (
        re.compile(r"Use Wow `@Summary` and `@Description` on commands and domain events\."),
        "Require API metadata only when commands or events are part of the API/domain contract.",
    ),
)

LOCAL_MARKDOWN_REF = re.compile(r"`((?:references/|\.\.?/)[^`]+\.md)`|\]\(((?:references/|\.\.?/)[^)]+\.md)\)")


def iter_skill_files(root: Path) -> list[Path]:
    skills_dir = root / "skills"
    if not skills_dir.exists():
        return []
    return sorted(path for path in skills_dir.rglob("*") if path.suffix in {".md", ".json"})


def find_unclosed_markdown_fence(text: str) -> int | None:
    opened: tuple[str, int, int] | None = None
    for line_no, line in enumerate(text.splitlines(), start=1):
        match = MARKDOWN_FENCE_PATTERN.match(line)
        if not match:
            continue
        fence = match.group("fence")
        info = match.group("info")
        if opened is None:
            opened = (fence[0], len(fence), line_no)
            continue
        fence_char, fence_length, _ = opened
        if fence[0] == fence_char and len(fence) >= fence_length and not info.strip():
            opened = None
    return opened[2] if opened else None


def iter_expected_outputs(value: object) -> Iterator[str]:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "expected_output" and isinstance(child, str):
                yield child
            else:
                yield from iter_expected_outputs(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_expected_outputs(child)


def lint(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in iter_skill_files(root):
        text = path.read_text(encoding="utf-8")
        if path.suffix == ".json":
            try:
                document = json.loads(text)
            except json.JSONDecodeError:
                findings.append(Finding(path.relative_to(root), 1, "Invalid JSON file."))
                continue
            expected_output_lines = [
                line_no
                for line_no, line in enumerate(text.splitlines(), start=1)
                if '"expected_output"' in line
            ]
            lines_to_lint = [
                (
                    expected_output_lines[index] if index < len(expected_output_lines) else 1,
                    expected_output,
                )
                for index, expected_output in enumerate(iter_expected_outputs(document))
            ]
        else:
            lines_to_lint = list(enumerate(text.splitlines(), start=1))
        for line_no, line in lines_to_lint:
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
            if path.suffix != ".md":
                continue
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
        if path.suffix == ".md":
            unclosed_fence_line = find_unclosed_markdown_fence(text)
            if unclosed_fence_line is not None:
                findings.append(
                    Finding(path.relative_to(root), unclosed_fence_line, "Unclosed Markdown code fence.")
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
