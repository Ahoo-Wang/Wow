#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Iterator
from dataclasses import dataclass
from html import unescape
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
    language: str | None = None
    in_comment: bool = False
    parse_code_tags: bool = True
    pending_tag: str | None = None
    raw_text_tag: str | None = None


@dataclass(frozen=True)
class MarkdownLinkReferenceDestination:
    valid: bool
    has_title: bool = False
    pending_title_closer: str | None = None


ASSERT_THAT_PATTERN = re.compile(r"\bassertThat\s*\(")
ASSERT_THAT_GUIDANCE_TARGET = r"(?:AssertJ(?:'s|\s*的)?\s+)?`?\s*assertThat\s*\("
ENGLISH_NEGATIVE_ASSERT_THAT_ACTION = (
    r"(?:use|using|call|calling|prefer|write|writing|rely(?:ing)?\s+on)"
)
PASSIVE_NEGATIVE_ASSERT_THAT_ACTION = re.compile(
    r"(?:(?:should|must)\s+not\s+be\s+(?:used|called)"
    r"|is\s+not\s+(?:allowed|permitted))\b",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_AS_API_COMPLEMENT = re.compile(
    r"\s+as\s+(?:(?:an?|the)\s+)?"
    r"(?:(?:Kotlin|test)\s+)?"
    r"assertions?(?:\s+(?:API|method|library|style|helper))?\b",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_TO_ASSERT_COMPLEMENT = re.compile(
    r"\s+to\s+(?:write|make|perform|express)\s+"
    r"(?:(?:Kotlin|test)\s+)?assertions?\b",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_ALLOWED_COMPLEMENT_TAIL = re.compile(
    r"\s*(?:$|[.,;:!?，；：。！？]"
    r"|(?:in|for|within|when|with|without|instead|because|since|so|which|that"
    r"|and|but|or|however|yet|except|unless|where|while|although|though)\b)",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_EXCEPTION_INTRO = re.compile(
    r"[,;:]|\b(?:and|but|or|however|yet|except|unless|where|while|although|though)\b",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_WORD_EXCEPTION_INTRO = frozenset(
    {
        "although",
        "and",
        "but",
        "however",
        "or",
        "though",
        "where",
        "while",
        "yet",
    }
)
PASSIVE_ASSERT_THAT_POSITIVE_EXCEPTION = re.compile(
    r"\s*(?:(?:in|for|within)\b[^,;:.!?，；：。！？]{0,80}\s+)?"
    r"(?:"
    r"(?:(?:it|developers?|users?|callers?)\s+)?"
    r"(?:may|can|could|might|should)\s+"
    r"(?:(?:also|still)\s+)*"
    r"(?:be\s+(?:used|called|allowed|permitted)|(?:use|call))\b"
    r"|\b(?:it\s+)?(?:remains?|is|are)\s+(?:still\s+)?"
    r"(?:allowed|permitted)\b"
    r"|(?:(?:the\s+)?(?:method|call|API)|its\s+use)\s+"
    r"(?:remains?|is|are)\s+(?:still\s+)?(?:allowed|permitted)\b"
    r"|(?:(?:developers?|users?|callers?)\s+(?:should\s+)?)?"
    r"continue\s+(?:using|calling|to\s+(?:use|call))\b"
    r"|(?:prefer|keep)\s+(?:using|calling|to\s+(?:use|call))\b"
    r"|(?:using|calling)\s+it\s+(?:is|remains)\s+(?:still\s+)?"
    r"(?:allowed|permitted)\b"
    r"|(?:use|call)\s+it\b"
    r")",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_NEGATIVE_EXCEPTION = re.compile(
    r"\s*(?:(?:it|(?:the\s+)?(?:method|call|API)|its\s+use)\s+)?"
    r"(?:"
    r"(?:should|must|may|can)\s+(?:(?:also|still)\s+)*"
    r"(?:not|never)\s+(?:be\s+)?(?:used|called)\b"
    r"|(?:cannot|can't)\s+(?:be\s+)?(?:used|called)\b"
    r"|(?:should|must)\s+(?:(?:also|still)\s+)*"
    r"be\s+(?:avoided|forbidden|prohibited|discouraged)\b"
    r"|(?:is|remains)\s+(?:(?:also|still)\s+)*"
    r"(?:not\s+(?:allowed|permitted)"
    r"|forbidden|prohibited|discouraged)\b"
    r"|(?:do\s+not|never)\s+(?:use|call)\b"
    r"|avoid\s+(?:using|calling)\b"
    r")",
    re.IGNORECASE,
)
PASSIVE_ASSERT_THAT_DIRECT_LABEL_SUFFIX = re.compile(
    r"(?:(?:the\s+)?AssertJ(?:'s|\s*的)?|the)\s*$",
    re.IGNORECASE,
)
MARKDOWN_GUIDANCE_SENTENCE_TERMINATORS = ".!?。！？"
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
MARKDOWN_HTML_TAG_START = re.compile(
    r"</?(?P<name>[A-Za-z][A-Za-z0-9-]*)(?=[ \t\r\n/>]|$)",
    re.IGNORECASE,
)
MARKDOWN_HTML_RAW_TEXT_TAGS = frozenset(
    {"script", "style", "textarea"}
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
        if (
            action is not None
            and passive_assert_that_action_prohibits_api(
                line,
                action.end(),
            )
        ):
            yield occurrence.start(), action.end()


def passive_assert_that_action_prohibits_api(
    line: str,
    action_end: int,
) -> bool:
    complement = normalize_markdown_inline_presentation(
        line[action_end:]
    )
    complement_kind = re.match(
        r"\s+(?:as|to)\b",
        complement,
        re.IGNORECASE,
    )
    if complement_kind is None:
        return not passive_assert_that_has_positive_exception(
            complement
        )
    allowed_complement = (
        PASSIVE_ASSERT_THAT_AS_API_COMPLEMENT.match(complement)
        or PASSIVE_ASSERT_THAT_TO_ASSERT_COMPLEMENT.match(
            complement
        )
    )
    if allowed_complement is None:
        return False
    tail = complement[allowed_complement.end():]
    if PASSIVE_ASSERT_THAT_ALLOWED_COMPLEMENT_TAIL.match(tail) is None:
        return False
    return not passive_assert_that_has_positive_exception(
        tail
    )


def passive_assert_that_has_positive_exception(text: str) -> bool:
    for intro in PASSIVE_ASSERT_THAT_EXCEPTION_INTRO.finditer(text):
        normalized_intro = intro.group(0).lower()
        if normalized_intro in {"except", "unless"}:
            return True
        clause = text[intro.end():]
        if PASSIVE_ASSERT_THAT_POSITIVE_EXCEPTION.match(
            clause
        ):
            return True
        if (
            normalized_intro
            in PASSIVE_ASSERT_THAT_WORD_EXCEPTION_INTRO
            and PASSIVE_ASSERT_THAT_NEGATIVE_EXCEPTION.match(
                clause
            )
            is None
            and normalized_intro not in {"and", "or"}
        ):
            return True
    return False


def find_matching_square_bracket(
    text: str,
    opening_index: int,
) -> int | None:
    depth = 0
    escaped = False
    for index in range(opening_index, len(text)):
        character = text[index]
        if escaped:
            escaped = False
            continue
        if character == "\\":
            escaped = True
        elif character == "[":
            depth += 1
        elif character == "]":
            depth -= 1
            if depth == 0:
                return index
    return None


def strip_markdown_inline_link_destinations(text: str) -> str:
    visible: list[str] = []
    index = 0
    while index < len(text):
        if text[index] != "[":
            visible.append(text[index])
            index += 1
            continue
        label_end = find_matching_square_bracket(text, index)
        if label_end is None:
            visible.append(text[index])
            index += 1
            continue
        destination_start = label_end + 1
        destination_end: int | None = None
        if (
            destination_start < len(text)
            and text[destination_start] == "("
        ):
            destination_end = find_matching_parenthesis(
                text,
                destination_start,
            )
        elif (
            destination_start < len(text)
            and text[destination_start] == "["
        ):
            destination_end = find_matching_square_bracket(
                text,
                destination_start,
            )
        if destination_end is None:
            visible.append(text[index + 1:label_end])
            index = label_end + 1
            continue
        visible.append(text[index + 1:label_end])
        index = destination_end + 1
    return "".join(visible)


def strip_markdown_inline_html_tags(text: str) -> str:
    visible: list[str] = []
    index = 0
    while index < len(text):
        inline_token_end: int | None = None
        for opening, closing in (
            ("<!--", "-->"),
            ("<?", "?>"),
            ("<![CDATA[", "]]>"),
        ):
            if not text.startswith(opening, index):
                continue
            closing_index = text.find(
                closing,
                index + len(opening),
            )
            if closing_index >= 0:
                inline_token_end = closing_index + len(closing)
            break
        if inline_token_end is not None:
            index = inline_token_end
            continue
        if (
            text.startswith("<!", index)
            and index + 2 < len(text)
            and text[index + 2].isupper()
        ):
            declaration_end = text.find(">", index + 3)
            if declaration_end >= 0:
                index = declaration_end + 1
                continue
        tag_match = MARKDOWN_HTML_TAG_START.match(text, index)
        if tag_match is None:
            visible.append(text[index])
            index += 1
            continue
        tag_end = find_markdown_html_tag_end(text, index)
        if tag_end is None:
            visible.append(text[index])
            index += 1
            continue
        index = tag_end
    return "".join(visible)


def normalize_markdown_inline_presentation(text: str) -> str:
    visible_text = strip_markdown_inline_link_destinations(text)
    visible_text = strip_markdown_inline_html_tags(visible_text)
    return unescape(re.sub(r"[`*_~]+", "", visible_text))


def strip_active_markdown_guidance_prefixes(
    line: str,
    current_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ],
) -> str:
    remaining = line
    for prefix in current_prefixes:
        if isinstance(prefix, MarkdownBlockquotePrefix):
            blockquote_match = MARKDOWN_BLOCKQUOTE_PREFIX.match(
                remaining
            )
            if blockquote_match is None:
                break
            remaining = remaining[blockquote_match.end():]
            continue
        leading_spaces = len(remaining) - len(
            remaining.lstrip(" ")
        )
        if leading_spaces < prefix.content_indent:
            break
        remaining = remaining[prefix.content_indent:]
    return remaining


def markdown_guidance_continuation_content(
    line: str,
    current_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ],
) -> str | None:
    remaining = strip_active_markdown_guidance_prefixes(
        line.expandtabs(tabsize=4),
        current_prefixes,
    )
    content, prefixes = parse_explicit_markdown_container(
        remaining
    )
    if prefixes:
        return None
    content = content.lstrip()
    if (
        not content
        or markdown_line_interrupts_paragraph(content)
    ):
        return None
    return content


def assert_that_guidance_context(
    lines_to_lint: list[tuple[int, str, str | None]],
    context_groups: list[int],
    index: int,
) -> str:
    line_no, line, language = lines_to_lint[index]
    current_content, current_prefixes = (
        parse_explicit_markdown_container(
            line.expandtabs(tabsize=4)
        )
    )
    normalized_line = normalize_markdown_inline_presentation(
        line
    ).rstrip()
    if (
        not ASSERT_THAT_PATTERN.search(line)
        or not normalized_line
        or normalized_line[-1]
        in MARKDOWN_GUIDANCE_SENTENCE_TERMINATORS
        or MARKDOWN_ATX_HEADING_PATTERN.match(
            current_content
        )
    ):
        return line

    context_lines = [line]
    previous_line_no = line_no
    current_context_group = context_groups[index]
    for next_index in range(index + 1, len(lines_to_lint)):
        (
            next_line_no,
            next_line,
            next_language,
        ) = lines_to_lint[next_index]
        if (
            context_groups[next_index] != current_context_group
            or next_language != language
            or next_line_no not in {
                previous_line_no,
                previous_line_no + 1,
            }
        ):
            break
        content = markdown_guidance_continuation_content(
            next_line,
            current_prefixes,
        )
        if content is None:
            break
        normalized_content = (
            normalize_markdown_inline_presentation(content)
            .lstrip()
        )
        if (
            len(context_lines) == 1
            and re.match(
                r"(?:as|to)\b",
                normalized_content,
                re.IGNORECASE,
            )
            is None
        ):
            break
        if ASSERT_THAT_PATTERN.search(content):
            break
        context_lines.append(content)
        previous_line_no = next_line_no
        if (
            normalized_content
            and normalized_content[-1]
            in MARKDOWN_GUIDANCE_SENTENCE_TERMINATORS
        ):
            break
    return " ".join(context_lines)


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


def strip_markdown_html_container_prefix(
    line: str,
    container: MarkdownContainer,
) -> str | None:
    if container == MarkdownContainer():
        return line
    return strip_markdown_container_prefix(line, container)


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


def parse_markdown_html_attributes(
    attributes: str,
) -> dict[str, str]:
    parsed: dict[str, str] = {}
    index = 0
    while index < len(attributes):
        while (
            index < len(attributes)
            and attributes[index].isspace()
        ):
            index += 1
        if index >= len(attributes) or attributes[index] == "/":
            break

        name_start = index
        while (
            index < len(attributes)
            and not attributes[index].isspace()
            and attributes[index] not in {"=", "/", ">"}
        ):
            index += 1
        if index == name_start:
            index += 1
            continue
        name = attributes[name_start:index].lower()

        while (
            index < len(attributes)
            and attributes[index].isspace()
        ):
            index += 1
        value = ""
        if index < len(attributes) and attributes[index] == "=":
            index += 1
            while (
                index < len(attributes)
                and attributes[index].isspace()
            ):
                index += 1
            if (
                index < len(attributes)
                and attributes[index] in {"'", '"'}
            ):
                quote = attributes[index]
                index += 1
                value_start = index
                while (
                    index < len(attributes)
                    and attributes[index] != quote
                ):
                    index += 1
                value = attributes[value_start:index]
                if index < len(attributes):
                    index += 1
            else:
                value_start = index
                while (
                    index < len(attributes)
                    and not attributes[index].isspace()
                    and attributes[index] not in {"/", ">"}
                ):
                    index += 1
                value = attributes[value_start:index]
        parsed[name] = value
    return parsed


def find_markdown_html_code_language(
    attributes: str,
) -> str | None:
    classes = parse_markdown_html_attributes(
        attributes
    ).get("class", "")
    for class_name in classes.split():
        normalized = class_name.lower()
        if normalized.startswith("language-"):
            return normalized.removeprefix("language-")
    return None


def find_markdown_html_tag_end(
    line: str,
    tag_start: int,
) -> int | None:
    quote: str | None = None
    for index in range(tag_start, len(line)):
        character = line[index]
        if quote is not None:
            if character == quote:
                quote = None
            continue
        if character in {"'", '"'}:
            quote = character
        elif character == ">":
            return index + 1
    return None


def append_markdown_html_segment(
    segments: list[tuple[str, str | None]],
    content: str,
    language: str | None,
) -> None:
    if not content:
        return
    if segments and segments[-1][1] == language:
        previous_content, _ = segments[-1]
        segments[-1] = previous_content + content, language
        return
    segments.append((content, language))


@dataclass
class MarkdownHtmlCodeState:
    line: str
    segments: list[tuple[str, str | None]]
    language: str | None
    in_comment: bool
    pending_tag: str | None
    raw_text_tag: str | None
    index: int = 0


def markdown_html_code_state_result(
    state: MarkdownHtmlCodeState,
) -> tuple[
    list[tuple[str, str | None]],
    str | None,
    bool,
    str | None,
    str | None,
]:
    return (
        state.segments,
        state.language,
        state.in_comment,
        state.pending_tag,
        state.raw_text_tag,
    )


def update_markdown_html_tag_state(
    state: MarkdownHtmlCodeState,
    tag: str,
    tag_name: str,
    is_closing: bool,
    attributes: str,
    visible_content: str,
) -> None:
    is_self_closing = tag.rstrip().endswith("/>")
    if tag_name == "code" and is_closing:
        append_markdown_html_segment(
            state.segments,
            visible_content,
            state.language,
        )
        state.language = None
    elif tag_name == "code":
        state.language = (
            find_markdown_html_code_language(attributes)
            or ""
        )
        append_markdown_html_segment(
            state.segments,
            visible_content,
            state.language,
        )
        if is_self_closing:
            state.language = None
    else:
        append_markdown_html_segment(
            state.segments,
            visible_content,
            state.language,
        )

    if (
        tag_name in MARKDOWN_HTML_RAW_TEXT_TAGS
        and not is_closing
        and not is_self_closing
    ):
        state.raw_text_tag = tag_name
    elif is_closing and tag_name == state.raw_text_tag:
        state.raw_text_tag = None


def consume_pending_markdown_html_tag(
    state: MarkdownHtmlCodeState,
) -> bool:
    pending_tag = state.pending_tag
    if pending_tag is None:
        return True
    combined_tag = f"{pending_tag}\n{state.line}"
    tag_match = MARKDOWN_HTML_TAG_START.match(combined_tag)
    if tag_match is None:
        append_markdown_html_segment(
            state.segments,
            state.line,
            state.language,
        )
        state.pending_tag = None
        state.index = len(state.line)
        return False

    tag_end = find_markdown_html_tag_end(
        combined_tag,
        tag_match.end(),
    )
    if tag_end is None:
        append_markdown_html_segment(
            state.segments,
            state.line,
            state.language,
        )
        state.pending_tag = combined_tag
        state.index = len(state.line)
        return False

    consumed = max(0, tag_end - len(pending_tag) - 1)
    tag = combined_tag[:tag_end]
    update_markdown_html_tag_state(
        state=state,
        tag=tag,
        tag_name=tag_match.group("name").lower(),
        is_closing=tag.startswith("</"),
        attributes=combined_tag[tag_match.end():tag_end - 1],
        visible_content=state.line[:consumed],
    )
    state.index = consumed
    state.pending_tag = None
    return True


def consume_markdown_html_raw_text(
    state: MarkdownHtmlCodeState,
) -> bool:
    raw_text_tag = state.raw_text_tag
    if raw_text_tag is None:
        return True
    raw_text_end = re.search(
        rf"</{re.escape(raw_text_tag)}(?=[ \t/>]|$)",
        state.line[state.index:],
        re.IGNORECASE,
    )
    if raw_text_end is None:
        append_markdown_html_segment(
            state.segments,
            state.line[state.index:],
            state.language,
        )
        state.index = len(state.line)
        return False

    closing_end = find_markdown_html_tag_end(
        state.line,
        state.index + raw_text_end.end(),
    )
    if closing_end is None:
        append_markdown_html_segment(
            state.segments,
            state.line[state.index:],
            state.language,
        )
        state.pending_tag = state.line[
            state.index + raw_text_end.start():
        ]
        state.index = len(state.line)
        return False

    append_markdown_html_segment(
        state.segments,
        state.line[state.index:closing_end],
        state.language,
    )
    state.index = closing_end
    state.raw_text_tag = None
    return True


def consume_markdown_html_comment(
    state: MarkdownHtmlCodeState,
) -> bool:
    comment_end = state.line.find("-->", state.index)
    if comment_end < 0:
        append_markdown_html_segment(
            state.segments,
            state.line[state.index:],
            state.language,
        )
        state.index = len(state.line)
        return False
    append_markdown_html_segment(
        state.segments,
        state.line[state.index:comment_end + 3],
        state.language,
    )
    state.index = comment_end + 3
    state.in_comment = False
    return True


def consume_next_markdown_html_tag(
    state: MarkdownHtmlCodeState,
) -> bool:
    tag_start = state.line.find("<", state.index)
    if tag_start < 0:
        append_markdown_html_segment(
            state.segments,
            state.line[state.index:],
            state.language,
        )
        state.index = len(state.line)
        return False

    append_markdown_html_segment(
        state.segments,
        state.line[state.index:tag_start],
        state.language,
    )
    if state.line.startswith("<!--", tag_start):
        state.index = tag_start
        state.in_comment = True
        return True

    tag_match = MARKDOWN_HTML_TAG_START.match(
        state.line,
        tag_start,
    )
    if tag_match is None:
        append_markdown_html_segment(
            state.segments,
            state.line[tag_start],
            state.language,
        )
        state.index = tag_start + 1
        return True

    tag_end = find_markdown_html_tag_end(
        state.line,
        tag_match.end(),
    )
    if tag_end is None:
        append_markdown_html_segment(
            state.segments,
            state.line[tag_start:],
            state.language,
        )
        state.pending_tag = state.line[tag_start:]
        state.index = len(state.line)
        return False

    tag = state.line[tag_start:tag_end]
    update_markdown_html_tag_state(
        state=state,
        tag=tag,
        tag_name=tag_match.group("name").lower(),
        is_closing=state.line.startswith("</", tag_start),
        attributes=state.line[tag_match.end():tag_end - 1],
        visible_content=tag,
    )
    state.index = tag_end
    return True


def split_markdown_html_code_segments(
    line: str,
    current_language: str | None,
    current_in_comment: bool,
    current_pending_tag: str | None,
    current_raw_text_tag: str | None,
) -> tuple[
    list[tuple[str, str | None]],
    str | None,
    bool,
    str | None,
    str | None,
]:
    state = MarkdownHtmlCodeState(
        line=line,
        segments=[],
        language=current_language,
        in_comment=current_in_comment,
        pending_tag=current_pending_tag,
        raw_text_tag=current_raw_text_tag,
    )
    if not consume_pending_markdown_html_tag(state):
        return markdown_html_code_state_result(state)
    while state.index < len(line):
        if state.raw_text_tag is not None:
            if not consume_markdown_html_raw_text(state):
                break
            continue
        if state.in_comment:
            if not consume_markdown_html_comment(state):
                break
            continue
        if not consume_next_markdown_html_tag(state):
            break
    return markdown_html_code_state_result(state)


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
        if markdown_line_starts_list_item_outside_container(
            line,
            paragraph_container,
        ):
            return False
        container_content = strip_available_markdown_paragraph_prefixes(
            line,
            paragraph_container,
        )
    content = line if container_content is None else container_content
    return not markdown_line_interrupts_paragraph(content)


def markdown_line_starts_list_item_outside_container(
    line: str,
    paragraph_container: MarkdownContainer,
) -> bool:
    content = strip_available_markdown_paragraph_prefixes(
        line,
        paragraph_container,
    )
    list_match = MARKDOWN_LIST_ITEM_PATTERN.match(content)
    if list_match is None:
        return False
    return parse_markdown_list_item(list_match).item_indent <= 3


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
                    parse_code_tags=(
                        markdown_html_block_parses_code_tags(
                            paragraph_content
                        )
                    ),
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
                parse_code_tags=(
                    markdown_html_block_parses_code_tags(content)
                ),
            )
    return None


def markdown_html_block_parses_code_tags(
    content: str,
) -> bool:
    raw_tag_match = MARKDOWN_HTML_RAW_TAG_START.match(content)
    if raw_tag_match:
        return raw_tag_match.group("tag").lower() == "pre"
    return not (
        MARKDOWN_HTML_COMMENT_START.match(content)
        or MARKDOWN_HTML_PROCESSING_INSTRUCTION_START.match(content)
        or MARKDOWN_HTML_CDATA_START.match(content)
        or MARKDOWN_HTML_DECLARATION_START.match(content)
    )


def strip_markdown_reference_indent(line: str) -> str | None:
    leading_spaces = len(line) - len(line.lstrip(" "))
    if leading_spaces > 3:
        return None
    return line[leading_spaces:]


def parse_markdown_link_reference_label(line: str) -> str | None:
    content = strip_markdown_reference_indent(line)
    if content is None or not content.startswith("["):
        return None
    index = 1
    label_length = 0
    label_has_non_whitespace = False
    while index < len(content):
        character = content[index]
        if character == "\\" and index + 1 < len(content):
            label_has_non_whitespace = (
                label_has_non_whitespace
                or not content[index + 1].isspace()
            )
            index += 2
            label_length += 1
            continue
        if character == "[":
            return None
        if character == "]":
            break
        label_has_non_whitespace = (
            label_has_non_whitespace
            or not character.isspace()
        )
        index += 1
        label_length += 1
    if (
        index >= len(content)
        or label_length == 0
        or not label_has_non_whitespace
        or label_length > 999
        or index + 1 >= len(content)
        or content[index + 1] != ":"
    ):
        return None
    return content[index + 2:]


def parse_markdown_link_reference_title(
    text: str,
) -> tuple[str, str | None]:
    content = text.strip()
    if not content:
        return "invalid", None
    opener = content[0]
    closer = {
        '"': '"',
        "'": "'",
        "(": ")",
    }.get(opener)
    if closer is None:
        return "invalid", None
    index = 1
    while index < len(content):
        character = content[index]
        if character == "\\" and index + 1 < len(content):
            index += 2
            continue
        if character == closer:
            if content[index + 1:].strip():
                return "invalid", None
            return "complete", None
        if opener == "(" and character == "(":
            return "invalid", None
        index += 1
    return "pending", closer


def skip_markdown_link_reference_spaces(
    text: str,
    index: int,
) -> int:
    while index < len(text) and text[index] in " \t":
        index += 1
    return index


def find_markdown_angle_destination_end(
    text: str,
    index: int,
) -> int | None:
    index += 1
    while index < len(text):
        character = text[index]
        if character == "\\" and index + 1 < len(text):
            index += 2
            continue
        if character == "<":
            return None
        if character == ">":
            return index + 1
        index += 1
    return None


def find_markdown_bare_destination_end(
    text: str,
    index: int,
) -> int | None:
    destination_start = index
    parenthesis_depth = 0
    while index < len(text) and text[index] not in " \t":
        character = text[index]
        if character == "\\" and index + 1 < len(text):
            index += 2
            continue
        if character == "(":
            parenthesis_depth += 1
        elif character == ")":
            if parenthesis_depth == 0:
                return None
            parenthesis_depth -= 1
        index += 1
    if index == destination_start or parenthesis_depth:
        return None
    return index


def parse_markdown_link_reference_title_suffix(
    text: str,
    index: int,
) -> MarkdownLinkReferenceDestination:
    separator_start = index
    index = skip_markdown_link_reference_spaces(text, index)
    had_separator = index > separator_start
    if index == len(text):
        return MarkdownLinkReferenceDestination(valid=True)
    if not had_separator:
        return MarkdownLinkReferenceDestination(valid=False)
    title_status, pending_closer = (
        parse_markdown_link_reference_title(text[index:])
    )
    if title_status == "complete":
        return MarkdownLinkReferenceDestination(
            valid=True,
            has_title=True,
        )
    if title_status == "pending":
        return MarkdownLinkReferenceDestination(
            valid=False,
            pending_title_closer=pending_closer,
        )
    return MarkdownLinkReferenceDestination(valid=False)


def parse_markdown_link_reference_destination(
    text: str,
) -> MarkdownLinkReferenceDestination:
    index = skip_markdown_link_reference_spaces(text, 0)
    if index >= len(text):
        return MarkdownLinkReferenceDestination(valid=False)
    if text[index] == "<":
        destination_end = find_markdown_angle_destination_end(
            text,
            index,
        )
    else:
        destination_end = find_markdown_bare_destination_end(
            text,
            index,
        )
    if destination_end is None:
        return MarkdownLinkReferenceDestination(valid=False)
    return parse_markdown_link_reference_title_suffix(
        text,
        destination_end,
    )


def is_markdown_link_reference_definition(line: str) -> bool:
    remainder = parse_markdown_link_reference_label(line)
    if remainder is None:
        return False
    return parse_markdown_link_reference_destination(
        remainder
    ).valid


def strip_markdown_reference_container(
    line: str,
    container: MarkdownContainer,
) -> str | None:
    content = strip_markdown_container_prefix(line, container)
    if content is None and container.explicit_prefixes:
        content = strip_available_markdown_paragraph_prefixes(
            line,
            container,
        )
    if content is None:
        return None
    return content


def find_markdown_link_reference_title_end(
    lines: list[str],
    start_index: int,
    container: MarkdownContainer,
    pending_closer: str,
) -> int | None:
    for index in range(start_index, len(lines)):
        content = strip_markdown_reference_container(
            lines[index].expandtabs(tabsize=4),
            container,
        )
        if content is None or not content.strip():
            return None
        character_index = 0
        while character_index < len(content):
            character = content[character_index]
            if (
                character == "\\"
                and character_index + 1 < len(content)
            ):
                character_index += 2
                continue
            if pending_closer == ")" and character == "(":
                return None
            if character == pending_closer:
                if content[character_index + 1:].strip():
                    return None
                return index
            character_index += 1
    return None


def find_optional_markdown_link_reference_title_end(
    lines: list[str],
    start_index: int,
    container: MarkdownContainer,
) -> int | None:
    if start_index >= len(lines):
        return None
    content = strip_markdown_reference_container(
        lines[start_index].expandtabs(tabsize=4),
        container,
    )
    if content is None:
        return None
    title_status, pending_closer = (
        parse_markdown_link_reference_title(content)
    )
    if title_status == "complete":
        return start_index
    if title_status != "pending" or pending_closer is None:
        return None
    return find_markdown_link_reference_title_end(
        lines,
        start_index + 1,
        container,
        pending_closer,
    )


def match_markdown_link_reference_definition(
    lines: list[str],
    start_index: int,
    active_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ],
) -> tuple[int, MarkdownContainer] | None:
    content, container = next(
        iter_markdown_container_contents(
            lines[start_index].expandtabs(tabsize=4),
            active_prefixes,
        )
    )
    remainder = parse_markdown_link_reference_label(content)
    if remainder is None:
        return None

    if remainder.strip():
        destination = parse_markdown_link_reference_destination(
            remainder
        )
        if (
            not destination.valid
            and destination.pending_title_closer is None
        ):
            return None
        definition_end = start_index
    else:
        destination_index = start_index + 1
        if destination_index >= len(lines):
            return None
        destination_content = strip_markdown_reference_container(
            lines[destination_index].expandtabs(tabsize=4),
            container,
        )
        if (
            destination_content is None
            or markdown_line_interrupts_paragraph(
                destination_content
            )
        ):
            return None
        destination = parse_markdown_link_reference_destination(
            destination_content
        )
        if (
            not destination.valid
            and destination.pending_title_closer is None
        ):
            return None
        definition_end = destination_index

    if destination.pending_title_closer is not None:
        title_end = find_markdown_link_reference_title_end(
            lines,
            definition_end + 1,
            container,
            destination.pending_title_closer,
        )
        if title_end is None:
            return None
        definition_end = title_end
    elif not destination.has_title:
        title_end = find_optional_markdown_link_reference_title_end(
            lines,
            definition_end + 1,
            container,
        )
        if title_end is not None:
            definition_end = title_end
    return definition_end, container


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
        or is_markdown_link_reference_definition(content)
        or MARKDOWN_LIST_ITEM_PATTERN.match(content)
    ):
        return None
    return container


@dataclass
class MarkdownAnalysisState:
    lines_to_lint: list[tuple[int, str, str | None]]
    unclosed_fence_lines: list[int]
    opened: tuple[
        str,
        int,
        int,
        str,
        MarkdownContainer,
    ] | None = None
    html_block: MarkdownHtmlBlock | None = None
    paragraph_container: MarkdownContainer | None = None
    link_reference_definition_end: int = -1
    active_list_prefixes: tuple[
        MarkdownBlockquotePrefix | MarkdownListPrefix,
        ...,
    ] = ()


def markdown_html_block_ends(
    html_block: MarkdownHtmlBlock,
    content: str,
) -> bool:
    if html_block.end_pattern is None:
        return not content.strip()
    return html_block.end_pattern.search(content) is not None


def analyze_markdown_html_content(
    content: str,
    html_block: MarkdownHtmlBlock,
) -> tuple[
    list[tuple[str, str | None]],
    MarkdownHtmlBlock,
]:
    if html_block.parse_code_tags:
        (
            html_segments,
            next_language,
            next_in_comment,
            next_pending_tag,
            next_raw_text_tag,
        ) = split_markdown_html_code_segments(
            content,
            html_block.language,
            html_block.in_comment,
            html_block.pending_tag,
            html_block.raw_text_tag,
        )
    else:
        html_segments = [(content, None)]
        next_language = None
        next_in_comment = False
        next_pending_tag = None
        next_raw_text_tag = None
    return html_segments, MarkdownHtmlBlock(
        end_pattern=html_block.end_pattern,
        container=html_block.container,
        language=next_language,
        in_comment=next_in_comment,
        parse_code_tags=html_block.parse_code_tags,
        pending_tag=next_pending_tag,
        raw_text_tag=next_raw_text_tag,
    )


def append_markdown_html_segments(
    state: MarkdownAnalysisState,
    line_no: int,
    segments: list[tuple[str, str | None]],
) -> None:
    state.lines_to_lint.extend(
        (line_no, segment, language)
        for segment, language in segments
    )


def analyze_open_markdown_fence_line(
    state: MarkdownAnalysisState,
    line_no: int,
    source_line: str,
    line: str,
) -> str:
    opened = state.opened
    assert opened is not None
    (
        fence_char,
        fence_length,
        opened_line,
        language,
        container,
    ) = opened
    if not is_line_in_markdown_container(line, container):
        state.unclosed_fence_lines.append(opened_line)
        state.opened = None
        return "retry"

    state.paragraph_container = None
    closing_fence = match_markdown_closing_fence(line, container)
    if (
        closing_fence
        and closing_fence.marker[0] == fence_char
        and len(closing_fence.marker) >= fence_length
        and not closing_fence.info.strip()
    ):
        state.lines_to_lint.append((line_no, source_line, None))
        state.opened = None
    else:
        state.lines_to_lint.append(
            (line_no, source_line, language)
        )
    return "handled"


def analyze_open_markdown_html_line(
    state: MarkdownAnalysisState,
    line_no: int,
    line: str,
) -> str:
    html_block = state.html_block
    assert html_block is not None
    html_content = strip_markdown_html_container_prefix(
        line,
        html_block.container,
    )
    if html_content is None:
        state.html_block = None
        return "retry"

    state.paragraph_container = None
    html_segments, next_html_block = (
        analyze_markdown_html_content(
            html_content,
            html_block,
        )
    )
    append_markdown_html_segments(
        state,
        line_no,
        html_segments,
    )
    state.html_block = (
        None
        if markdown_html_block_ends(html_block, html_content)
        else next_html_block
    )
    return "handled"


def analyze_active_markdown_block_line(
    state: MarkdownAnalysisState,
    line_no: int,
    source_line: str,
    line: str,
) -> str | None:
    if state.opened is not None:
        return analyze_open_markdown_fence_line(
            state,
            line_no,
            source_line,
            line,
        )
    if state.html_block is not None:
        return analyze_open_markdown_html_line(
            state,
            line_no,
            line,
        )
    return None


def analyze_markdown_link_reference_line(
    state: MarkdownAnalysisState,
    source_lines: list[str],
    line_index: int,
    source_line: str,
) -> bool:
    line_no = line_index + 1
    if line_index <= state.link_reference_definition_end:
        state.lines_to_lint.append((line_no, source_line, None))
        state.paragraph_container = None
        return True
    if state.paragraph_container is not None:
        return False

    link_reference_match = (
        match_markdown_link_reference_definition(
            source_lines,
            line_index,
            state.active_list_prefixes,
        )
    )
    if link_reference_match is None:
        return False
    (
        state.link_reference_definition_end,
        link_reference_container,
    ) = link_reference_match
    state.lines_to_lint.append((line_no, source_line, None))
    state.active_list_prefixes = (
        get_active_markdown_list_prefixes(
            link_reference_container
        )
    )
    state.paragraph_container = None
    return True


def analyze_existing_markdown_paragraph_line(
    state: MarkdownAnalysisState,
    line_no: int,
    source_line: str,
    line: str,
) -> bool:
    if is_markdown_setext_heading_underline(
        line,
        state.paragraph_container,
    ):
        state.lines_to_lint.append((line_no, source_line, None))
        state.active_list_prefixes = (
            get_active_markdown_list_prefixes(
                state.paragraph_container
            )
        )
        state.paragraph_container = None
        return True
    if is_markdown_paragraph_continuation(
        line,
        state.paragraph_container,
    ):
        state.lines_to_lint.append((line_no, source_line, None))
        return True
    return False


def analyze_new_markdown_html_block_line(
    state: MarkdownAnalysisState,
    line_no: int,
    line: str,
) -> bool:
    html_match = match_markdown_html_block(
        line,
        state.active_list_prefixes,
        state.paragraph_container,
    )
    if html_match is None:
        return False
    html_content, html_block = html_match
    state.paragraph_container = None
    state.active_list_prefixes = (
        get_active_markdown_list_prefixes(
            html_block.container
        )
    )
    html_segments, next_html_block = (
        analyze_markdown_html_content(
            html_content,
            html_block,
        )
    )
    append_markdown_html_segments(
        state,
        line_no,
        html_segments,
    )
    if not markdown_html_block_ends(
        html_block,
        html_content,
    ):
        state.html_block = next_html_block
    return True


def analyze_markdown_fence_or_text_line(
    state: MarkdownAnalysisState,
    line_no: int,
    source_line: str,
    line: str,
) -> None:
    opening_fence = match_markdown_fence(
        line,
        state.active_list_prefixes,
        state.paragraph_container,
    )
    state.lines_to_lint.append((line_no, source_line, None))
    if opening_fence is None:
        _, line_container = next(
            iter_markdown_container_contents(
                line,
                state.active_list_prefixes,
            )
        )
        state.paragraph_container = (
            find_markdown_paragraph_container(
                line,
                state.active_list_prefixes,
            )
        )
        state.active_list_prefixes = (
            get_active_markdown_list_prefixes(
                line_container
            )
        )
        return

    state.paragraph_container = None
    state.active_list_prefixes = (
        get_active_markdown_list_prefixes(
            opening_fence.container
        )
    )
    info_parts = opening_fence.info.strip().split(maxsplit=1)
    language = info_parts[0].lower() if info_parts else ""
    state.opened = (
        opening_fence.marker[0],
        len(opening_fence.marker),
        line_no,
        language,
        opening_fence.container,
    )


def analyze_markdown(text: str) -> tuple[list[tuple[int, str, str | None]], list[int]]:
    source_lines = text.splitlines()
    state = MarkdownAnalysisState(
        lines_to_lint=[],
        unclosed_fence_lines=[],
    )
    for line_index, source_line in enumerate(source_lines):
        line_no = line_index + 1
        line = source_line.expandtabs(tabsize=4)
        while True:
            active_action = analyze_active_markdown_block_line(
                state,
                line_no,
                source_line,
                line,
            )
            if active_action == "retry":
                continue
            if active_action == "handled":
                break
            if analyze_markdown_link_reference_line(
                state,
                source_lines,
                line_index,
                source_line,
            ):
                break
            if analyze_existing_markdown_paragraph_line(
                state,
                line_no,
                source_line,
                line,
            ):
                break
            if analyze_new_markdown_html_block_line(
                state,
                line_no,
                line,
            ):
                break
            analyze_markdown_fence_or_text_line(
                state,
                line_no,
                source_line,
                line,
            )
            break
    if state.opened:
        state.unclosed_fence_lines.append(state.opened[2])
    return state.lines_to_lint, state.unclosed_fence_lines


def analyze_skill_markdown(
    text: str,
) -> tuple[list[tuple[int, str, str | None]], list[int]]:
    source_lines = text.splitlines()
    if not source_lines or source_lines[0].rstrip(" \t") != "---":
        return analyze_markdown(text)
    closing_index = next(
        (
            index
            for index, line in enumerate(source_lines[1:], start=1)
            if line.rstrip(" \t") == "---"
        ),
        None,
    )
    if closing_index is None:
        return analyze_markdown(text)

    frontmatter_lines = [
        (line_no, line, None)
        for line_no, line in enumerate(
            source_lines[:closing_index + 1],
            start=1,
        )
    ]
    body_lines, body_unclosed_fence_lines = analyze_markdown(
        "\n".join(source_lines[closing_index + 1:])
    )
    line_offset = closing_index + 1
    return (
        [
            *frontmatter_lines,
            *[
                (
                    line_no + line_offset,
                    line,
                    fence_language,
                )
                for line_no, line, fence_language in body_lines
            ],
        ],
        [
            line_no + line_offset
            for line_no in body_unclosed_fence_lines
        ],
    )


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
        context_groups: list[int] = []
        if path.suffix == ".json":
            try:
                json.loads(text)
            except json.JSONDecodeError:
                findings.append(Finding(path.relative_to(root), 1, "Invalid JSON file."))
                continue
            lines_to_lint = []
            for context_group, (
                line_no,
                expected_output,
            ) in enumerate(
                iter_json_object_key_values(
                    text,
                    "expected_output",
                )
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
                context_groups.extend(
                    [context_group]
                    * len(expected_output_lines_to_lint)
                )
                unclosed_fence_lines.extend(
                    line_no for _ in expected_output_unclosed_fence_lines
                )
        else:
            lines_to_lint, unclosed_fence_lines = analyze_skill_markdown(
                text
            )
            context_groups = [0] * len(lines_to_lint)
        for line_index, (
            line_no,
            line,
            fence_language,
        ) in enumerate(lines_to_lint):
            for pattern, message in PATTERNS:
                if pattern is ASSERT_THAT_PATTERN and fence_language == "java":
                    continue
                if (
                    pattern is ASSERT_THAT_PATTERN
                    and ASSERT_THAT_PATTERN.search(line)
                    and all_assert_that_occurrences_are_negative_guidance(
                        assert_that_guidance_context(
                            lines_to_lint,
                            context_groups,
                            line_index,
                        )
                    )
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
