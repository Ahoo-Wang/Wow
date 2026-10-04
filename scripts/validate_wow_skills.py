#!/usr/bin/env python3
"""Validate the repository-owned Wow Skills package with the Python stdlib."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any


EXPECTED_SKILLS = {
    "wow-client",
    "wow-data-query",
    "wow-develop",
    "wow-migrate",
    "wow-view-definition",
    "wow-view-host",
}
NAME_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
RESOURCE_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_/])((?:references|assets|scripts)/[A-Za-z0-9_.\-/]+)"
)
NON_RUNTIME_RESOURCE_MARKERS = ("agents/", "evals/")
PARENT_PATH_PATTERN = re.compile(r"(?<![A-Za-z0-9_.-])\.\.[/\\]")
EXPLICIT_ABSOLUTE_FILESYSTEM_PATTERN = re.compile(
    r"(?<![A-Za-z0-9])(?:file://|~[/\\]|\$(?:HOME|CODEX_HOME)[/\\]|"
    r"\$\{(?:HOME|CODEX_HOME)\}[/\\]|[A-Za-z]:[/\\]|\\\\[^\\\s]+\\[^\\\s]+)"
)
UNIX_ABSOLUTE_PATH_PATTERN = re.compile(
    r"(?<![A-Za-z0-9:./])/(?!/)[A-Za-z0-9._{}-]+(?:/[A-Za-z0-9._{}-]+)*"
)
GLOB_ARGUMENT_PATTERN = re.compile(r"--glob(?:=|\s+)(?:'[^']*'|\"[^\"]*\"|\S+)")
HTTP_ROUTE_PREFIX_PATTERN = re.compile(
    r"^/(?:api(?:/|$)|v\d+(?:/|$)|actuator(?:/|$)|swagger-ui(?:[./]|$)|wow(?:/|$))",
    re.IGNORECASE,
)
HTTP_PATH_CONTEXT_PATTERN = re.compile(
    r"\b(?:http|endpoint|route|request|browser|get|post|put|patch|delete|head|options)\b",
    re.IGNORECASE,
)
FILESYSTEM_PATH_CONTEXT_PATTERN = re.compile(
    r"\b(?:read|open|inspect|load|write|edit|delete|remove|copy|move|execute|run|source|"
    r"cat|head|tail|less|more|rm|cp|mv|"
    r"file|directory|filesystem|config(?:uration)?\s+(?:file|path|lives)|path\s+(?:is|at))\b",
    re.IGNORECASE,
)
FILESYSTEM_ROOTS = {
    "Users", "Volumes", "bin", "boot", "dev", "etc", "home",
    "lib", "lib64", "media", "mnt", "opt", "private", "proc", "root",
    "run", "sbin", "secrets", "srv", "sys", "tmp", "usr", "var", "workspace", "workspaces",
}
FILESYSTEM_SUFFIXES = {
    ".env", ".key", ".pem",
}
# A description is read on every turn to pick a skill; past this it fails.
DESCRIPTION_MAX_WORDS = 60
# `claude plugin eval` suites: evals/<case>/prompt.md + graders/*.md.
MIN_EVAL_CASES = 3
IGNORED_EVAL_ENTRIES = {"results"}
SUITE_TAGS = {"activation", "behavior"}
PROMPT_KEYS = {
    "schema_version", "name", "description", "tags", "plugins", "runs", "expected_outcome",
    "model", "max_turns", "timeout_seconds", "allowed_tools", "append_system_prompt", "env",
}
POSITIVE_INTEGER_KEYS = ("runs", "max_turns", "timeout_seconds")
GRADER_TYPES = {"regex", "tool_order", "tool_used", "file_exists", "llm", "baseline"}
GRADER_ARMS = {"with-only", "both"}
# Grader keys by type, as the `claude plugin eval` grader schema lists them.
GRADER_COMMON_KEYS = {"type", "name", "weight", "arm"}
GRADER_KEYS = {
    "regex": {"pattern", "flags", "match", "target"},
    "tool_used": {"tool", "input_match", "min", "max"},
    "tool_order": {"before", "after"},
    "file_exists": {"path", "exists"},
    "llm": {"criteria", "focus"},
    "baseline": {"baseline_file", "criteria"},
}
# `(?i)`-style groups are Python/PCRE; the CLI compiles JavaScript regexes.
INLINE_REGEX_FLAGS = re.compile(r"\(\?[a-z]+\)")
REGEX_FLAGS = re.compile(r"[dgimsuvy]+")


def _is_glob_argument(line: str, start: int, end: int) -> bool:
    return any(match.start() <= start and end <= match.end() for match in GLOB_ARGUMENT_PATTERN.finditer(line))


def _is_http_route(path: str, line: str) -> bool:
    return (
        HTTP_ROUTE_PREFIX_PATTERN.search(path) is not None
        or ("{" in path and "}" in path)
        or (
            HTTP_PATH_CONTEXT_PATTERN.search(line) is not None
            and FILESYSTEM_PATH_CONTEXT_PATTERN.search(line) is None
        )
    )


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate key {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> Any:
    raise ValueError(f"invalid constant {value}")


def _validate_filesystem_paths(
    source: Path,
    value: str,
    errors: list[str],
    *,
    script: bool = False,
) -> None:
    if PARENT_PATH_PATTERN.search(value):
        errors.append(f"{source}: runtime content references a parent path")
    if EXPLICIT_ABSOLUTE_FILESYSTEM_PATTERN.search(value):
        errors.append(f"{source}: runtime content references an absolute filesystem path")
        return
    for line in value.splitlines():
        for match in UNIX_ABSOLUTE_PATH_PATTERN.finditer(line):
            path = match.group(0)
            if script and (path == "/dev/null" or _is_glob_argument(line, match.start(), match.end())):
                continue
            parts = path.lstrip("/").split("/")
            filesystem_marker = (
                parts[0] in FILESYSTEM_ROOTS
                or any(part.startswith(".") for part in parts)
                or any(path.endswith(suffix) for suffix in FILESYSTEM_SUFFIXES)
            )
            if _is_http_route(path, line) and not filesystem_marker:
                continue
            if filesystem_marker or FILESYSTEM_PATH_CONTEXT_PATTERN.search(line) is not None:
                errors.append(f"{source}: runtime content references an absolute filesystem path")
                return


def _validate_runtime_text(source: Path, value: str, errors: list[str]) -> None:
    for marker in NON_RUNTIME_RESOURCE_MARKERS:
        if marker in value:
            errors.append(f"{source}: runtime content references maintainer-only content: {marker}")
    _validate_filesystem_paths(source, value, errors)


def _validate_script_text(source: Path, value: str, errors: list[str]) -> None:
    _validate_filesystem_paths(source, value, errors, script=True)


def _scalar(raw: str, source: Path, line: int, errors: list[str]) -> str | None:
    value = raw.strip()
    if not value:
        errors.append(f"{source}:{line}: missing scalar value")
        return None
    if value[0] != '\"':
        errors.append(f"{source}:{line}: value must be a double-quoted string")
        return None
    try:
        parsed = json.loads(value)
    except (ValueError, RecursionError):
        errors.append(f"{source}:{line}: invalid quoted scalar")
        return None
    if not isinstance(parsed, str):
        errors.append(f"{source}:{line}: scalar must be a string")
        return None
    return parsed


def _frontmatter(path: Path, errors: list[str]) -> tuple[dict[str, str], str]:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        errors.append(f"{path}: cannot read UTF-8 text: {exc}")
        return {}, ""

    lines = text.splitlines()
    if not lines or lines[0] != "---":
        errors.append(f"{path}: frontmatter must start with ---")
        return {}, text
    try:
        closing = lines.index("---", 1)
    except ValueError:
        errors.append(f"{path}: frontmatter is not closed")
        return {}, ""

    values: dict[str, str] = {}
    for index, line in enumerate(lines[1:closing], start=2):
        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)", line)
        if match is None:
            errors.append(f"{path}:{index}: frontmatter must use flat key: value entries")
            continue
        key, raw = match.groups()
        if key in values:
            errors.append(f"{path}:{index}: duplicate frontmatter key {key!r}")
            continue
        value = _scalar(raw, path, index, errors)
        if value is not None:
            values[key] = value

    return values, "\n".join(lines[closing + 1 :]).strip()


def _validate_skill_file(skill_dir: Path, errors: list[str]) -> str:
    skill_file = skill_dir / "SKILL.md"
    if skill_dir.is_symlink() or skill_file.is_symlink():
        errors.append(f"{skill_file}: Skill directories and SKILL.md must not be links")
        return ""
    if not skill_file.is_file():
        errors.append(f"{skill_file}: missing SKILL.md")
        return ""

    metadata, body = _frontmatter(skill_file, errors)
    unknown = sorted(set(metadata) - {"name", "description"})
    if unknown:
        errors.append(f"{skill_file}: unsupported frontmatter keys: {', '.join(unknown)}")

    name = metadata.get("name", "")
    description = metadata.get("description", "")
    if name != skill_dir.name:
        errors.append(f"{skill_file}: name {name!r} must match directory {skill_dir.name!r}")
    if not NAME_PATTERN.fullmatch(name) or len(name) > 64:
        errors.append(f"{skill_file}: invalid skill name {name!r}")
    if not description.strip() or len(description) > 1024 or "<" in description or ">" in description:
        errors.append(f"{skill_file}: description must be 1-1024 characters without angle brackets")
    words = len(description.split())
    if words > DESCRIPTION_MAX_WORDS:
        errors.append(f"{skill_file}: description has {words} words (limit {DESCRIPTION_MAX_WORDS})")
    _validate_runtime_text(skill_file, description, errors)
    if not body:
        errors.append(f"{skill_file}: body must not be empty")
    return body


def _validate_openai_yaml(skill_dir: Path, errors: list[str]) -> None:
    agents_dir = skill_dir / "agents"
    path = agents_dir / "openai.yaml"
    if agents_dir.is_symlink() or path.is_symlink() or not _contained(path, skill_dir):
        errors.append(f"{path}: agents and openai.yaml must stay inside the Skill and must not be links")
        return
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        errors.append(f"{path}: cannot read UTF-8 text: {exc}")
        return

    content = [(index, line) for index, line in enumerate(lines, start=1) if line.strip()]
    if not content or content[0][1] != "interface:":
        errors.append(f"{path}: expected a top-level interface mapping")
        return

    values: dict[str, str] = {}
    for index, line in content[1:]:
        match = re.fullmatch(r"  ([a-z_]+):\s*(.*)", line)
        if match is None:
            errors.append(f"{path}:{index}: expected a two-space-indented interface scalar")
            continue
        key, raw = match.groups()
        if key in values:
            errors.append(f"{path}:{index}: duplicate interface key {key!r}")
            continue
        value = _scalar(raw, path, index, errors)
        if value is not None:
            values[key] = value

    required = {"display_name", "short_description", "default_prompt"}
    if set(values) != required:
        errors.append(f"{path}: interface keys must be exactly {', '.join(sorted(required))}")
        return
    if not values["display_name"].strip() or len(values["display_name"]) > 64:
        errors.append(f"{path}: display_name must be 1-64 characters")
    if not values["short_description"].strip() or not 25 <= len(values["short_description"]) <= 64:
        errors.append(f"{path}: short_description must be 25-64 characters")
    _validate_runtime_text(path, values["default_prompt"], errors)
    skill_token = re.compile(
        rf"(?<![A-Za-z0-9_-])\${re.escape(skill_dir.name)}(?![A-Za-z0-9_-])"
    )
    if skill_token.search(values["default_prompt"]) is None:
        errors.append(f"{path}: default_prompt must reference ${skill_dir.name}")


def _contained(candidate: Path, parent: Path) -> bool:
    try:
        candidate.resolve().relative_to(parent.resolve())
        return True
    except (OSError, ValueError):
        return False


def _validate_resources(skill_dir: Path, body: str, errors: list[str]) -> None:
    referenced = set(RESOURCE_PATTERN.findall(body))
    for raw in sorted(referenced):
        relative = PurePosixPath(raw)
        if relative.is_absolute() or ".." in relative.parts:
            errors.append(f"{skill_dir / 'SKILL.md'}: resource path escapes the Skill: {raw}")
            continue
        target = skill_dir.joinpath(*relative.parts)
        if not _contained(target, skill_dir):
            errors.append(f"{skill_dir / 'SKILL.md'}: resource path escapes the Skill: {raw}")
        elif not target.exists():
            errors.append(f"{skill_dir / 'SKILL.md'}: referenced resource does not exist: {raw}")
        elif target.is_symlink() or not target.is_file():
            errors.append(f"{skill_dir / 'SKILL.md'}: referenced resource must be a regular file: {raw}")

    for directory in ("references", "assets", "scripts"):
        root = skill_dir / directory
        if root.is_symlink():
            errors.append(f"{root}: resource directories must stay inside the Skill and must not be links")
            continue
        if not root.exists():
            continue
        if not _contained(root, skill_dir):
            errors.append(f"{root}: resource directories must stay inside the Skill and must not be links")
            continue
        if not root.is_dir():
            errors.append(f"{root}: resource root must be a regular directory")
            continue
        for path in sorted(root.rglob("*")):
            if path.is_symlink():
                errors.append(f"{path}: resource links are not allowed")
                continue
            if path.is_dir():
                continue
            if not path.is_file():
                errors.append(f"{path}: resource must be a regular file")
                continue
            relative = path.relative_to(skill_dir).as_posix()
            if relative not in referenced:
                errors.append(f"{path}: resource is not referenced directly from SKILL.md")

    documents = [(skill_dir / "SKILL.md", body)]
    for directory in ("references", "assets"):
        document_root = skill_dir / directory
        if document_root.is_dir() and not document_root.is_symlink() and _contained(document_root, skill_dir):
            for path in sorted(document_root.rglob("*.md")):
                if path.is_symlink() or not _contained(path, skill_dir):
                    continue
                try:
                    documents.append((path, path.read_text(encoding="utf-8")))
                except (OSError, UnicodeError) as exc:
                    errors.append(f"{path}: cannot read UTF-8 text: {exc}")
    script_root = skill_dir / "scripts"
    if script_root.is_dir() and not script_root.is_symlink() and _contained(script_root, skill_dir):
        for path in sorted(script_root.rglob("*")):
            if path.is_symlink() or not path.is_file() or not _contained(path, skill_dir):
                continue
            try:
                lines = path.read_text(encoding="utf-8").splitlines()
                if lines and lines[0].startswith("#!"):
                    lines = lines[1:]
                _validate_script_text(path, "\n".join(lines), errors)
            except (OSError, UnicodeError) as exc:
                errors.append(f"{path}: cannot read UTF-8 text: {exc}")
    for source, text in documents:
        _validate_runtime_text(source, text, errors)


def _eval_value(raw: str) -> str | list[str]:
    """A flat YAML scalar or flow list, as `claude plugin eval` frontmatter writes them."""
    value = raw.strip()
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        return [item.strip().strip("'\"") for item in inner.split(",")] if inner else []
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == value[-1] == '"':
        parsed = json.loads(value)
        if not isinstance(parsed, str):
            raise ValueError("quoted value must be a string")
        return parsed
    return value


def _eval_frontmatter(path: Path) -> tuple[dict[str, str | list[str]], str]:
    """The frontmatter and body of an eval prompt.md or grader; raises ValueError when malformed."""
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "---":
        raise ValueError("frontmatter must start with ---")
    try:
        closing = lines.index("---", 1)
    except ValueError:
        raise ValueError("frontmatter is not closed") from None
    values: dict[str, str | list[str]] = {}
    for number, line in enumerate(lines[1:closing], start=2):
        if not line.strip():
            continue
        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_-]*):\s*(.*)", line)
        if match is None:
            raise ValueError(f"line {number}: frontmatter must use flat key: value entries")
        key, raw = match.groups()
        if key in values:
            raise ValueError(f"line {number}: duplicate key {key!r}")
        values[key] = _eval_value(raw)
    return values, "\n".join(lines[closing + 1 :]).strip()


def _validate_suite(skill_dir: Path, errors: list[str]) -> None:
    """A `claude plugin eval` suite: evals/<case>/prompt.md plus evals/<case>/graders/*.md."""
    skill = skill_dir.name
    eval_dir = skill_dir / "evals"
    if eval_dir.is_symlink() or not _contained(eval_dir, skill_dir):
        errors.append(f"{eval_dir}: evals must stay inside the Skill and must not be a link")
        return
    if not eval_dir.is_dir():
        errors.append(f"{eval_dir}: missing eval suite (evals/<case>/prompt.md + graders/*.md)")
        return
    for legacy in ("activation.jsonl", "behavior.jsonl", "evals.json"):
        if (eval_dir / legacy).exists():
            errors.append(
                f"{eval_dir / legacy}: `claude plugin eval` does not read this format; "
                "write evals/<case>/prompt.md + graders/*.md"
            )
    cases = sorted(
        entry for entry in eval_dir.iterdir() if entry.name not in IGNORED_EVAL_ENTRIES and not entry.name.startswith(".")
    )
    own_skill = re.compile(rf"(?<![\w-]){re.escape(skill)}(?![\w-])")
    counts = {"activation": 0, "behavior": 0}
    fires = 0
    negatives = 0
    for case_dir in cases:
        location = f"{case_dir}"
        if case_dir.is_symlink() or not case_dir.is_dir():
            errors.append(f"{location}: an eval case must be a regular directory")
            continue
        if not NAME_PATTERN.fullmatch(case_dir.name):
            errors.append(f"{location}: case directory is not kebab-case")
        for entry in sorted(case_dir.rglob("*")):
            if entry.is_symlink():
                errors.append(f"{entry}: eval case links are not allowed")
        prompt = case_dir / "prompt.md"
        tags: list[str] = []
        if not prompt.is_file():
            errors.append(f"{prompt}: missing")
        else:
            try:
                data, body = _eval_frontmatter(prompt)
            except (OSError, UnicodeError, ValueError) as exc:
                errors.append(f"{prompt}: {exc}")
            else:
                for key in sorted(set(data) - PROMPT_KEYS):
                    errors.append(f"{prompt}: unknown key {key}")
                if "name" in data and data["name"] != case_dir.name:
                    errors.append(f"{prompt}: name {data['name']!r} must match directory {case_dir.name!r}")
                for key in POSITIVE_INTEGER_KEYS:
                    if key in data and not (isinstance(data[key], str) and re.fullmatch(r"[1-9]\d*", data[key])):
                        errors.append(f"{prompt}: {key} must be a positive integer")
                for key in ("tags", "allowed_tools"):
                    if key in data and not isinstance(data[key], list):
                        errors.append(f"{prompt}: {key} must be a list")
                raw_tags = data.get("tags", [])
                tags = raw_tags if isinstance(raw_tags, list) else []
                if not body:
                    errors.append(f"{prompt}: prompt body is empty")
        suites = [tag for tag in tags if tag in SUITE_TAGS]
        if len(suites) != 1:
            errors.append(f"{prompt}: tags must hold exactly one of {', '.join(sorted(SUITE_TAGS))}")
        else:
            counts[suites[0]] += 1

        graders_dir = case_dir / "graders"
        graders = sorted(graders_dir.glob("*.md")) if graders_dir.is_dir() and not graders_dir.is_symlink() else []
        if not graders:
            errors.append(f"{graders_dir}: needs at least one grader")
        case_fires = case_negatives = 0
        scored = False
        for grader in graders:
            try:
                data, body = _eval_frontmatter(grader)
            except (OSError, UnicodeError, ValueError) as exc:
                errors.append(f"{grader}: {exc}")
                continue
            kind = data.get("type")
            if kind not in GRADER_TYPES:
                errors.append(f"{grader}: unknown grader type {kind!r}")
                continue
            for key in sorted(set(data) - GRADER_COMMON_KEYS - GRADER_KEYS[kind]):
                errors.append(f"{grader}: unknown key {key} for a {kind} grader")
            arm = data.get("arm")
            if arm is not None and arm not in GRADER_ARMS:
                errors.append(f"{grader}: unknown arm {arm!r} (use {' or '.join(sorted(GRADER_ARMS))})")
            if kind == "llm" and not body and not data.get("criteria"):
                errors.append(f"{grader}: llm grader has no criteria")
            if kind == "regex":
                pattern = data.get("pattern")
                if not isinstance(pattern, str) or not pattern:
                    errors.append(f"{grader}: regex grader has no pattern")
                elif INLINE_REGEX_FLAGS.search(pattern):
                    errors.append(f"{grader}: inline flag groups are not JavaScript regex syntax; use `flags:`")
                flags = data.get("flags")
                if flags is not None and (not isinstance(flags, str) or not REGEX_FLAGS.fullmatch(flags)):
                    errors.append(f"{grader}: flags must be JavaScript regex flags (dgimsuvy), got {flags!r}")
            if kind in {"llm", "regex"}:
                scored = True
            if kind == "tool_used" and data.get("tool") == "Skill" and own_skill.search(str(data.get("input_match", ""))):
                if data.get("max") == "0":
                    case_negatives += 1
                    # Only the skill under test is loaded, so "it did not load" is the
                    # whole verdict of a negative case; without `arm: both` the
                    # default with/without ablation leaves it unscored.
                    if arm != "both":
                        errors.append(
                            f"{grader}: a negative trigger check must set `arm: both`, "
                            "or `--ablation with-without` leaves it unscored"
                        )
                else:
                    case_fires += 1
        fires += case_fires
        negatives += case_negatives
        if "behavior" in suites and not scored:
            errors.append(f"{case_dir}: a behavior case needs an llm or regex grader to score the answer")
        if "activation" in suites and case_fires + case_negatives != 1:
            errors.append(f"{case_dir}: an activation case holds exactly one tool_used Skill grader naming {skill}")
    if len(cases) < MIN_EVAL_CASES:
        errors.append(f"{eval_dir}: needs at least {MIN_EVAL_CASES} cases (evals/<case>/prompt.md), found {len(cases)}")
    if cases and fires == 0:
        errors.append(f"{eval_dir}: no case asserts that {skill} loads (a tool_used Skill grader naming it)")
    if cases and negatives == 0:
        errors.append(f"{eval_dir}: no negative case (a tool_used Skill grader naming {skill} with max: 0)")
    if cases and counts["behavior"] == 0:
        errors.append(f"{eval_dir}: no behavior case")


def validate_repository(root: Path) -> list[str]:
    errors: list[str] = []
    skills_root = root / "skills"
    manifest_path = skills_root / "plugins.json"
    if skills_root.is_symlink() or not skills_root.is_dir():
        return [f"{skills_root}: skills must be a regular directory, not a link"]
    if manifest_path.is_symlink() or not manifest_path.is_file() or not _contained(manifest_path, skills_root):
        return [f"{manifest_path}: plugin manifest must be a regular file inside skills"]
    try:
        manifest = json.loads(
            manifest_path.read_text(encoding="utf-8"),
            object_pairs_hook=_unique_json_object,
            parse_constant=_reject_json_constant,
        )
        plugins = manifest["plugins"]
        included = plugins[0]["skills"]["include"]
    except (OSError, UnicodeError, ValueError, RecursionError, KeyError, IndexError, TypeError) as exc:
        return [f"{manifest_path}: invalid plugin manifest: {exc}"]

    schema_version = manifest.get("schemaVersion")
    if (
        type(schema_version) is not int
        or schema_version != 1
        or not isinstance(plugins, list)
        or len(plugins) != 1
    ):
        errors.append(f"{manifest_path}: expected schemaVersion 1 and exactly one plugin")
        included = []
    if isinstance(plugins, list) and len(plugins) == 1 and isinstance(plugins[0], dict):
        plugin = plugins[0]
        for key in ("name", "version", "description"):
            value = plugin.get(key)
            if not isinstance(value, str) or not value.strip():
                errors.append(f"{manifest_path}: plugin {key} must be a non-empty string")
            elif key == "description":
                _validate_runtime_text(manifest_path, value, errors)
        interface = plugin.get("interface")
        if not isinstance(interface, dict):
            errors.append(f"{manifest_path}: plugin interface must be an object")
        else:
            for key in ("displayName", "defaultPrompt"):
                value = interface.get(key)
                if not isinstance(value, str) or not value.strip():
                    errors.append(f"{manifest_path}: plugin interface.{key} must be a non-empty string")
                elif key == "defaultPrompt":
                    _validate_runtime_text(manifest_path, value, errors)
    if not isinstance(included, list) or any(not isinstance(item, str) for item in included):
        errors.append(f"{manifest_path}: skills.include must be a list of Skill names")
        included_names: set[str] = set()
    else:
        included_names = set(included)
        if len(included_names) != len(included):
            errors.append(f"{manifest_path}: skills.include must not contain duplicates")

    actual_names = {
        path.name
        for path in skills_root.iterdir()
        if not path.is_symlink() and path.is_dir() and (path / "SKILL.md").exists()
    }
    if included_names != actual_names or actual_names != EXPECTED_SKILLS:
        errors.append(
            f"{manifest_path}: included, installed, and expected Skills must match: "
            f"{', '.join(sorted(EXPECTED_SKILLS))}"
        )

    for skill_name in sorted(EXPECTED_SKILLS):
        skill_dir = skills_root / skill_name
        if skill_dir.is_symlink() or not skill_dir.is_dir() or not _contained(skill_dir, skills_root):
            errors.append(f"{skill_dir}: Skill must be a regular directory inside skills")
            continue
        body = _validate_skill_file(skill_dir, errors)
        _validate_openai_yaml(skill_dir, errors)
        _validate_resources(skill_dir, body, errors)
        _validate_suite(skill_dir, errors)
    return sorted(errors)


def main(argv: list[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) > 1:
        print("usage: validate_wow_skills.py [repository-root]", file=sys.stderr)
        return 2
    root = Path(args[0]).resolve() if args else Path(__file__).resolve().parents[1]
    errors = validate_repository(root)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(
        f"Wow Skills validation passed: {len(EXPECTED_SKILLS)} Skills; "
        "eval suites are well-formed."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
