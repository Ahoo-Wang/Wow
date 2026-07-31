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


@dataclass(frozen=True)
class MarkdownListItem:
    item_indent: int
    content_indent: int
    content: str


@dataclass(frozen=True)
class MarkdownBlockquotePrefix:
    pass


@dataclass(frozen=True)
class MarkdownListPrefix:
    content_indent: int


@dataclass(frozen=True)
class MarkdownContainer:
    explicit_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ] = ()


@dataclass(frozen=True)
class MarkdownFence:
    marker: str
    info: str
    container: MarkdownContainer


@dataclass(frozen=True)
class MarkdownHtmlBlock:
    end_pattern: re.Pattern[str] | None
    container: MarkdownContainer


ASSERT_THAT_PATTERN = re.compile(r"\bassertThat\s*\(")
ASSERT_THAT_GUIDANCE_TARGET = r"(?:AssertJ(?:'s|\s*的)?\s+)?`?\s*assertThat\s*\("
ENGLISH_NEGATIVE_ASSERT_THAT_ACTION = (
    r"(?:use|using|call|calling|prefer|write|writing|rely(?:ing)?\s+on)"
)
PASSIVE_NEGATIVE_ASSERT_THAT_ACTION = re.compile(
    r"(?:(?:should|must)\s+not\s+be\s+(?:used|called)"
    r"|is\s+not\s+(?:allowed|permitted))\b"
    r"(?!\s+(?:as|to)\b)",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_DIRECT_LABEL_SUFFIX = re.compile(
    r"(?:(?:the\s+)?AssertJ(?:'s|\s*的)?|the)\s*$",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_SUBJECT_NOUN = re.compile(
    r"(?:method|api|call)\b",
    re.IGNORECASE,
)
MARKDOWN_TASK_CHECKBOX_PREFIX = re.compile(r"^\[[ xX]\][ \t]+")
MARKDOWN_INLINE_MARKER_SUFFIX = re.compile(r"[`*_~]+\s*$")
NEGATIVE_ASSERT_THAT_GUIDANCE = re.compile(
    r"(?:"
    rf"\bnot\s+(?:{ENGLISH_NEGATIVE_ASSERT_THAT_ACTION}\s+)?{ASSERT_THAT_GUIDANCE_TARGET}"
    rf"|\bnever\s+(?:{ENGLISH_NEGATIVE_ASSERT_THAT_ACTION}\s+)?{ASSERT_THAT_GUIDANCE_TARGET}"
    rf"|\bavoid\s+(?:{ENGLISH_NEGATIVE_ASSERT_THAT_ACTION}\s+)?{ASSERT_THAT_GUIDANCE_TARGET}"
    rf"|(?:不要|不应|请勿)[^,，;；:：.!！?？。\n]{{0,80}}(?:使用|调用)\s*{ASSERT_THAT_GUIDANCE_TARGET}"
    rf"|不使用\s*{ASSERT_THAT_GUIDANCE_TARGET}"
    r")",
    re.IGNORECASE,
)
LEGACY_WAIT_TIMEOUT_PATTERN = re.compile(r"\bCommand-Wait-Timout\b")
LEGACY_WAIT_TIMEOUT_GUIDANCE = re.compile(r"\blegacy\b.*\bcompatibility\b|\bcompatibility\b.*\blegacy\b")
MARKDOWN_FENCE_PATTERN = re.compile(r"^ {0,3}(?P<fence>`{3,}|~{3,})(?P<info>.*)$")
MARKDOWN_BLOCKQUOTE_PREFIX = re.compile(r"^ {0,3}>[ \t]?")
MARKDOWN_LIST_ITEM_PATTERN = re.compile(
    r"^(?P<indent> *)(?P<marker>[-+*]|\d{1,9}[.)])"
    r"(?:(?P<spacing>[ \t]+)(?P<content>.*)|$)"
)
MARKDOWN_HTML_RAW_TAG_START = re.compile(
    r"^ {0,3}<(?P<tag>script|pre|style|textarea)(?:[ \t]|>|$)",
    re.IGNORECASE,
)
MARKDOWN_HTML_RAW_TAG_END = re.compile(
    r"</(?:script|pre|style|textarea)>",
    re.IGNORECASE,
)
MARKDOWN_HTML_COMMENT_START = re.compile(r"^ {0,3}<!--")
MARKDOWN_HTML_COMMENT_END = re.compile(r"-->")
MARKDOWN_HTML_PROCESSING_INSTRUCTION_START = re.compile(r"^ {0,3}<\?")
MARKDOWN_HTML_PROCESSING_INSTRUCTION_END = re.compile(r"\?>")
MARKDOWN_HTML_DECLARATION_START = re.compile(r"^ {0,3}<![A-Z]")
MARKDOWN_HTML_DECLARATION_END = re.compile(r">")
MARKDOWN_HTML_CDATA_START = re.compile(r"^ {0,3}<!\[CDATA\[")
MARKDOWN_HTML_CDATA_END = re.compile(r"\]\]>")
MARKDOWN_HTML_BLOCK_TAG_START = re.compile(
    r"^ {0,3}</?(?:"
    r"address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|"
    r"dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|"
    r"frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|"
    r"nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|"
    r"tfoot|th|thead|title|tr|track|ul"
    r")(?:[ \t]+|/?>|$)",
    re.IGNORECASE,
)
MARKDOWN_HTML_TAG_NAME = r"[A-Za-z][A-Za-z0-9-]*"
MARKDOWN_HTML_ATTRIBUTE_NAME = r"[A-Za-z_:][A-Za-z0-9_.:-]*"
MARKDOWN_HTML_ATTRIBUTE_VALUE = r"(?:[^ \t\n\"'=<>`]+|'[^']*'|\"[^\"]*\")"
MARKDOWN_HTML_ATTRIBUTE = (
    rf"(?:[ \t]+{MARKDOWN_HTML_ATTRIBUTE_NAME}"
    rf"(?:[ \t]*=[ \t]*{MARKDOWN_HTML_ATTRIBUTE_VALUE})?)"
)
MARKDOWN_HTML_TYPE_7_START = re.compile(
    rf"^ {{0,3}}(?:"
    rf"<{MARKDOWN_HTML_TAG_NAME}(?:{MARKDOWN_HTML_ATTRIBUTE})*[ \t]*/?>"
    rf"|</{MARKDOWN_HTML_TAG_NAME}[ \t]*>"
    rf")[ \t]*$"
)
MARKDOWN_ATX_HEADING_PATTERN = re.compile(r"^ {0,3}#{1,6}(?:[ \t]+|$)")
MARKDOWN_SETEXT_HEADING_PATTERN = re.compile(r"^ {0,3}(?:=+|-+)[ \t]*$")
MARKDOWN_THEMATIC_BREAK_PATTERN = re.compile(
    r"^ {0,3}(?:(?:\*[ \t]*){3,}|(?:_[ \t]*){3,}|(?:-[ \t]*){3,})$"
)
MARKDOWN_INDENTED_CODE_PATTERN = re.compile(r"^ {4}")

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


def find_matching_parenthesis(text: str, opening_index: int) -> int | None:
    depth = 0
    quote: str | None = None
    escaped = False
    for index in range(opening_index, len(text)):
        character = text[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in {"'", '"'}:
            quote = character
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return index
    return None


def is_direct_passive_assert_that_subject(
    line: str,
    occurrence_start: int,
) -> bool:
    prefix, _ = parse_explicit_markdown_container(
        line[:occurrence_start]
    )
    heading_match = MARKDOWN_ATX_HEADING_PATTERN.match(prefix)
    if heading_match is not None:
        prefix = prefix[heading_match.end():]
    task_checkbox_match = MARKDOWN_TASK_CHECKBOX_PREFIX.match(prefix)
    if task_checkbox_match is not None:
        prefix = prefix[task_checkbox_match.end():]
    prefix = MARKDOWN_INLINE_MARKER_SUFFIX.sub("", prefix).rstrip()
    prefix = PASSIVE_ASSERT_THAT_DIRECT_LABEL_SUFFIX.sub("", prefix).rstrip()
    prefix = MARKDOWN_INLINE_MARKER_SUFFIX.sub("", prefix).rstrip()
    if not prefix:
        return True
    if prefix[-1] in ",;:.!?，；：。！？":
        return True
    return False


def skip_markdown_inline_subject_presentation(
    line: str,
    start: int,
) -> int:
    index = start
    while (
        index < len(line)
        and (line[index].isspace() or line[index] in "`*_~")
    ):
        index += 1
    return index


def iter_passive_negative_assert_that_guidance_spans(
    line: str,
) -> Iterator[tuple[int, int]]:
    for occurrence in ASSERT_THAT_PATTERN.finditer(line):
        if not is_direct_passive_assert_that_subject(
            line,
            occurrence.start(),
        ):
            continue
        closing_index = find_matching_parenthesis(line, occurrence.end() - 1)
        if closing_index is None:
            continue
        suffix_index = skip_markdown_inline_subject_presentation(
            line,
            closing_index + 1,
        )
        subject_noun = PASSIVE_ASSERT_THAT_SUBJECT_NOUN.match(
            line,
            suffix_index,
        )
        if subject_noun is not None:
            suffix_index = skip_markdown_inline_subject_presentation(
                line,
                subject_noun.end(),
            )
        action = PASSIVE_NEGATIVE_ASSERT_THAT_ACTION.match(line, suffix_index)
        if action is not None:
            yield occurrence.start(), action.end()


def all_assert_that_occurrences_are_negative_guidance(line: str) -> bool:
    guidance_spans = [
        match.span()
        for match in NEGATIVE_ASSERT_THAT_GUIDANCE.finditer(line)
    ]
    guidance_spans.extend(iter_passive_negative_assert_that_guidance_spans(line))
    return bool(guidance_spans) and all(
        any(start <= match.start() < end for start, end in guidance_spans)
        for match in ASSERT_THAT_PATTERN.finditer(line)
    )


def iter_skill_files(root: Path) -> list[Path]:
    skills_dir = root / "skills"
    if not skills_dir.exists():
        return []
    return sorted(path for path in skills_dir.rglob("*") if path.suffix in {".md", ".json"})


def split_markdown_blockquote_prefixes(
    line: str,
    max_depth: int | None = None,
) -> tuple[str, int]:
    remaining = line
    depth = 0
    while True:
        if max_depth is not None and depth >= max_depth:
            return remaining, depth
        blockquote_match = MARKDOWN_BLOCKQUOTE_PREFIX.match(remaining)
        if blockquote_match:
            remaining = remaining[blockquote_match.end() :]
            depth += 1
            continue
        return remaining, depth


def parse_markdown_list_item(match: re.Match[str]) -> MarkdownListItem:
    item_indent = len(match.group("indent"))
    marker_width = len(match.group("marker"))
    spacing = match.group("spacing")
    if spacing is None:
        return MarkdownListItem(
            item_indent=item_indent,
            content_indent=item_indent + marker_width + 1,
            content="",
        )
    padding = len(spacing) if len(spacing) <= 4 else 1
    return MarkdownListItem(
        item_indent=item_indent,
        content_indent=item_indent + marker_width + padding,
        content=spacing[padding:] + (match.group("content") or ""),
    )


def iter_markdown_container_contents(
    line: str,
    active_prefixes: tuple[MarkdownBlockquotePrefix | MarkdownListPrefix, ...],
) -> Iterator[tuple[str, MarkdownContainer]]:
    for prefix_count in range(len(active_prefixes), 0, -1):
        inherited_prefixes = active_prefixes[:prefix_count]
        inherited_container = MarkdownContainer(
            explicit_prefixes=inherited_prefixes,
        )
        remaining = strip_markdown_container_prefix(
            line,
            inherited_container,
        )
        if remaining is None:
            continue
        content, nested_prefixes = parse_explicit_markdown_container(remaining)
        yield content, MarkdownContainer(
            explicit_prefixes=(*inherited_prefixes, *nested_prefixes),
        )
        return

    remaining, explicit_prefixes = parse_explicit_markdown_container(line)
    if explicit_prefixes:
        yield remaining, MarkdownContainer(
            explicit_prefixes=explicit_prefixes,
        )
        return

    yield line, MarkdownContainer()


def is_valid_markdown_fence_opening(match: re.Match[str]) -> bool:
    fence = match.group("fence")
    info = match.group("info")
    return fence[0] != "`" or "`" not in info


def parse_explicit_markdown_container(
    line: str,
) -> tuple[
    str,
    tuple[MarkdownBlockquotePrefix | MarkdownListPrefix, ...],
]:
    remaining = line
    prefixes: list[MarkdownBlockquotePrefix | MarkdownListPrefix] = []
    while True:
        blockquote_match = MARKDOWN_BLOCKQUOTE_PREFIX.match(remaining)
        if blockquote_match:
            prefixes.append(MarkdownBlockquotePrefix())
            remaining = remaining[blockquote_match.end() :]
            continue

        if MARKDOWN_THEMATIC_BREAK_PATTERN.match(remaining):
            break
        list_match = MARKDOWN_LIST_ITEM_PATTERN.match(remaining)
        if not list_match:
            break
        list_item = parse_markdown_list_item(list_match)
        if list_item.item_indent > 3:
            break
        prefixes.append(MarkdownListPrefix(content_indent=list_item.content_indent))
        remaining = list_item.content

    return remaining, tuple(prefixes)


def match_markdown_fence(
    line: str,
    active_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ] = (),
    paragraph_container: MarkdownContainer | None = None,
) -> MarkdownFence | None:
    if paragraph_container is not None:
        paragraph_content = strip_markdown_container_prefix(
            line,
            paragraph_container,
        )
        if paragraph_content is not None:
            match = MARKDOWN_FENCE_PATTERN.match(paragraph_content)
            if match and is_valid_markdown_fence_opening(match):
                return MarkdownFence(
                    marker=match.group("fence"),
                    info=match.group("info"),
                    container=paragraph_container,
                )

    for content, container in iter_markdown_container_contents(
        line,
        active_prefixes,
    ):
        match = MARKDOWN_FENCE_PATTERN.match(content)
        if match and is_valid_markdown_fence_opening(match):
            return MarkdownFence(
                marker=match.group("fence"),
                info=match.group("info"),
                container=container,
            )
    return None


def strip_markdown_container_prefix(
    line: str,
    container: MarkdownContainer,
) -> str | None:
    if container.explicit_prefixes:
        remaining = line
        for index, prefix in enumerate(container.explicit_prefixes):
            if isinstance(prefix, MarkdownBlockquotePrefix):
                blockquote_match = MARKDOWN_BLOCKQUOTE_PREFIX.match(remaining)
                if not blockquote_match:
                    return None
                remaining = remaining[blockquote_match.end() :]
                continue

            if not remaining.strip():
                remaining_prefixes = container.explicit_prefixes[index:]
                if all(
                    isinstance(candidate, MarkdownListPrefix)
                    for candidate in remaining_prefixes
                ):
                    return ""
                return None
            leading_spaces = len(remaining) - len(remaining.lstrip(" "))
            if leading_spaces < prefix.content_indent:
                return None
            remaining = remaining[prefix.content_indent :]
        return remaining

    remaining, blockquote_depth = split_markdown_blockquote_prefixes(line)
    if blockquote_depth:
        return None
    return remaining


def strip_available_markdown_paragraph_prefixes(
    line: str,
    container: MarkdownContainer,
) -> str:
    remaining = line
    for prefix in container.explicit_prefixes:
        if isinstance(prefix, MarkdownBlockquotePrefix):
            blockquote_match = MARKDOWN_BLOCKQUOTE_PREFIX.match(remaining)
            if not blockquote_match:
                break
            remaining = remaining[blockquote_match.end() :]
            continue

        leading_spaces = len(remaining) - len(remaining.lstrip(" "))
        if leading_spaces < prefix.content_indent:
            break
        remaining = remaining[prefix.content_indent :]
    return remaining


def get_active_markdown_list_prefixes(
    container: MarkdownContainer,
) -> tuple[MarkdownBlockquotePrefix | MarkdownListPrefix, ...]:
    for index in range(len(container.explicit_prefixes) - 1, -1, -1):
        if isinstance(container.explicit_prefixes[index], MarkdownListPrefix):
            return container.explicit_prefixes[: index + 1]
    return ()


def is_line_in_markdown_container(line: str, container: MarkdownContainer) -> bool:
    if container == MarkdownContainer():
        return True
    return strip_markdown_container_prefix(line, container) is not None


def match_markdown_closing_fence(
    line: str,
    container: MarkdownContainer,
) -> MarkdownFence | None:
    remaining = strip_markdown_container_prefix(line, container)
    if remaining is None:
        return None
    match = MARKDOWN_FENCE_PATTERN.match(remaining)
    if not match:
        return None
    return MarkdownFence(
        marker=match.group("fence"),
        info=match.group("info"),
        container=container,
    )


def match_markdown_html_block_start(
    line: str,
    paragraph_open: bool,
) -> tuple[bool, re.Pattern[str] | None]:
    if MARKDOWN_HTML_RAW_TAG_START.match(line):
        return True, MARKDOWN_HTML_RAW_TAG_END
    if MARKDOWN_HTML_COMMENT_START.match(line):
        return True, MARKDOWN_HTML_COMMENT_END
    if MARKDOWN_HTML_PROCESSING_INSTRUCTION_START.match(line):
        return True, MARKDOWN_HTML_PROCESSING_INSTRUCTION_END
    if MARKDOWN_HTML_CDATA_START.match(line):
        return True, MARKDOWN_HTML_CDATA_END
    if MARKDOWN_HTML_DECLARATION_START.match(line):
        return True, MARKDOWN_HTML_DECLARATION_END
    if MARKDOWN_HTML_BLOCK_TAG_START.match(line):
        return True, None
    if not paragraph_open and MARKDOWN_HTML_TYPE_7_START.match(line):
        return True, None
    return False, None


def markdown_line_interrupts_paragraph(content: str) -> bool:
    if not content.strip():
        return True

    fence_match = MARKDOWN_FENCE_PATTERN.match(content)
    if fence_match and is_valid_markdown_fence_opening(fence_match):
        return True

    is_html_block, _ = match_markdown_html_block_start(
        content,
        paragraph_open=True,
    )
    if is_html_block:
        return True

    if (
        MARKDOWN_ATX_HEADING_PATTERN.match(content)
        or MARKDOWN_SETEXT_HEADING_PATTERN.match(content)
        or MARKDOWN_THEMATIC_BREAK_PATTERN.match(content)
        or MARKDOWN_BLOCKQUOTE_PREFIX.match(content)
    ):
        return True

    list_match = MARKDOWN_LIST_ITEM_PATTERN.match(content)
    if not list_match:
        return False
    list_item = parse_markdown_list_item(list_match)
    if list_item.item_indent > 3:
        return False
    if not list_item.content.strip():
        return False
    marker = list_match.group("marker")
    if marker[0].isdigit():
        return int(marker[:-1]) == 1
    return True


def is_markdown_paragraph_continuation(
    line: str,
    paragraph_container: MarkdownContainer | None,
) -> bool:
    if paragraph_container is None or not line.strip():
        return False
    container_content = strip_markdown_container_prefix(
        line,
        paragraph_container,
    )
    if container_content is None and paragraph_container.explicit_prefixes:
        container_content = strip_available_markdown_paragraph_prefixes(
            line,
            paragraph_container,
        )
    content = line if container_content is None else container_content
    return not markdown_line_interrupts_paragraph(content)


def is_markdown_setext_heading_underline(
    line: str,
    paragraph_container: MarkdownContainer | None,
) -> bool:
    if paragraph_container is None:
        return False
    paragraph_content = strip_markdown_container_prefix(
        line,
        paragraph_container,
    )
    return (
        paragraph_content is not None
        and MARKDOWN_SETEXT_HEADING_PATTERN.match(paragraph_content) is not None
    )


def match_markdown_html_block(
    line: str,
    active_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ],
    paragraph_container: MarkdownContainer | None,
) -> tuple[str, MarkdownHtmlBlock] | None:
    if paragraph_container is not None:
        paragraph_content = strip_markdown_container_prefix(
            line,
            paragraph_container,
        )
        if paragraph_content is not None:
            is_html_block, end_pattern = match_markdown_html_block_start(
                paragraph_content,
                paragraph_open=True,
            )
            if is_html_block:
                return paragraph_content, MarkdownHtmlBlock(
                    end_pattern=end_pattern,
                    container=paragraph_container,
                )

    for content, container in iter_markdown_container_contents(
        line,
        active_prefixes,
    ):
        is_html_block, end_pattern = match_markdown_html_block_start(
            content,
            paragraph_container == container,
        )
        if is_html_block:
            return content, MarkdownHtmlBlock(
                end_pattern=end_pattern,
                container=container,
            )
    return None


def find_markdown_paragraph_container(
    line: str,
    active_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ],
) -> MarkdownContainer | None:
    content, container = next(
        iter_markdown_container_contents(line, active_prefixes)
    )
    if not content.strip():
        return None
    if (
        MARKDOWN_ATX_HEADING_PATTERN.match(content)
        or MARKDOWN_THEMATIC_BREAK_PATTERN.match(content)
        or MARKDOWN_INDENTED_CODE_PATTERN.match(content)
        or MARKDOWN_LIST_ITEM_PATTERN.match(content)
    ):
        return None
    return container


def analyze_markdown(text: str) -> tuple[list[tuple[int, str, str | None]], list[int]]:
    lines_to_lint: list[tuple[int, str, str | None]] = []
    opened: tuple[str, int, int, str, MarkdownContainer] | None = None
    html_block: MarkdownHtmlBlock | None = None
    paragraph_container: MarkdownContainer | None = None
    unclosed_fence_lines: list[int] = []
    active_list_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ] = ()
    for line_no, source_line in enumerate(text.splitlines(), start=1):
        line = source_line.expandtabs(tabsize=4)
        while True:
            if opened is not None:
                fence_char, fence_length, opened_line, language, container = opened
                if not is_line_in_markdown_container(line, container):
                    unclosed_fence_lines.append(opened_line)
                    opened = None
                    continue

                paragraph_container = None
                closing_fence = match_markdown_closing_fence(line, container)
                if (
                    closing_fence
                    and closing_fence.marker[0] == fence_char
                    and len(closing_fence.marker) >= fence_length
                    and not closing_fence.info.strip()
                ):
                    lines_to_lint.append((line_no, source_line, None))
                    opened = None
                else:
                    lines_to_lint.append((line_no, source_line, language))
                break

            if html_block is not None:
                html_content = strip_markdown_container_prefix(line, html_block.container)
                if html_content is None:
                    html_block = None
                    continue
                paragraph_container = None
                lines_to_lint.append((line_no, source_line, None))
                if (
                    html_block.end_pattern is None
                    and not html_content.strip()
                ) or (
                    html_block.end_pattern is not None
                    and html_block.end_pattern.search(html_content)
                ):
                    html_block = None
                break

            if is_markdown_setext_heading_underline(
                line,
                paragraph_container,
            ):
                lines_to_lint.append((line_no, source_line, None))
                active_list_prefixes = get_active_markdown_list_prefixes(
                    paragraph_container
                )
                paragraph_container = None
                break

            if is_markdown_paragraph_continuation(
                line,
                paragraph_container,
            ):
                lines_to_lint.append((line_no, source_line, None))
                break

            html_match = match_markdown_html_block(
                line,
                active_list_prefixes,
                paragraph_container,
            )
            if html_match:
                html_content, candidate_html_block = html_match
                paragraph_container = None
                active_list_prefixes = get_active_markdown_list_prefixes(
                    candidate_html_block.container
                )
                lines_to_lint.append((line_no, source_line, None))
                if (
                    candidate_html_block.end_pattern is None
                    or not candidate_html_block.end_pattern.search(html_content)
                ):
                    html_block = candidate_html_block
                break

            opening_fence = match_markdown_fence(
                line,
                active_list_prefixes,
                paragraph_container,
            )
            lines_to_lint.append((line_no, source_line, None))
            if not opening_fence:
                _, line_container = next(
                    iter_markdown_container_contents(
                        line,
                        active_list_prefixes,
                    )
                )
                paragraph_container = find_markdown_paragraph_container(
                    line,
                    active_list_prefixes,
                )
                active_list_prefixes = get_active_markdown_list_prefixes(
                    line_container
                )
                break
            paragraph_container = None
            active_list_prefixes = get_active_markdown_list_prefixes(
                opening_fence.container
            )
            info_parts = opening_fence.info.strip().split(maxsplit=1)
            language = info_parts[0].lower() if info_parts else ""
            opened = (
                opening_fence.marker[0],
                len(opening_fence.marker),
                line_no,
                language,
                opening_fence.container,
            )
            break
    if opened:
        unclosed_fence_lines.append(opened[2])
    return lines_to_lint, unclosed_fence_lines


def find_unclosed_markdown_fence(text: str) -> int | None:
    _, unclosed_fence_lines = analyze_markdown(text)
    return unclosed_fence_lines[0] if unclosed_fence_lines else None


def iter_json_object_key_values(
    text: str,
    key: str,
) -> Iterator[tuple[int, object]]:
    decoder = json.JSONDecoder()
    index = 0
    while index < len(text):
        if text[index] != '"':
            index += 1
            continue
        start = index
        index += 1
        while index < len(text):
            if text[index] == "\\":
                index += 2
                continue
            if text[index] == '"':
                break
            index += 1
        if index >= len(text):
            break
        end = index
        suffix_index = end + 1
        while suffix_index < len(text) and text[suffix_index].isspace():
            suffix_index += 1
        if suffix_index < len(text) and text[suffix_index] == ":":
            try:
                decoded_key = json.loads(text[start:end + 1])
            except json.JSONDecodeError:
                decoded_key = None
            if decoded_key == key:
                value_index = suffix_index + 1
                while (
                    value_index < len(text)
                    and text[value_index].isspace()
                ):
                    value_index += 1
                try:
                    value, _ = decoder.raw_decode(text, value_index)
                except json.JSONDecodeError:
                    pass
                else:
                    yield text.count("\n", 0, start) + 1, value
        index = end + 1


def lint(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in iter_skill_files(root):
        text = path.read_text(encoding="utf-8")
        unclosed_fence_lines: list[int] = []
        if path.suffix == ".json":
            try:
                json.loads(text)
            except json.JSONDecodeError:
                findings.append(Finding(path.relative_to(root), 1, "Invalid JSON file."))
                continue
            lines_to_lint = []
            for line_no, expected_output in iter_json_object_key_values(
                text,
                "expected_output",
            ):
                if not isinstance(expected_output, str):
                    continue
                expected_output_lines_to_lint, expected_output_unclosed_fence_lines = (
                    analyze_markdown(expected_output)
                )
                lines_to_lint.extend(
                    (line_no, logical_line, fence_language)
                    for _, logical_line, fence_language in expected_output_lines_to_lint
                )
                unclosed_fence_lines.extend(
                    line_no for _ in expected_output_unclosed_fence_lines
                )
        else:
            lines_to_lint, unclosed_fence_lines = analyze_markdown(text)
        for line_no, line, fence_language in lines_to_lint:
            for pattern, message in PATTERNS:
                if pattern is ASSERT_THAT_PATTERN and fence_language == "java":
                    continue
                if (
                    pattern is ASSERT_THAT_PATTERN
                    and ASSERT_THAT_PATTERN.search(line)
                    and all_assert_that_occurrences_are_negative_guidance(line)
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
        for unclosed_fence_line in unclosed_fence_lines:
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
