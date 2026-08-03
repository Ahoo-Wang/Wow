#!/usr/bin/env python3
"""Validate the repository-owned Wow Skills package with the Python stdlib."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any


EXPECTED_SKILLS = {
    "wow-debug",
    "wow-develop",
    "wow-migrate",
    "wow-review",
}
NAME_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
RESOURCE_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_/])((?:references|assets|scripts)/[A-Za-z0-9_.\-/]+)"
)
MARKDOWN_LINK_PATTERN = re.compile(r"\]\(([^)\s]+)")


def _scalar(raw: str, source: Path, line: int, errors: list[str]) -> str | None:
    value = raw.strip()
    if not value:
        errors.append(f"{source}:{line}: missing scalar value")
        return None
    if value[0] == '\"':
        try:
            parsed = json.loads(value)
        except (ValueError, RecursionError):
            errors.append(f"{source}:{line}: invalid quoted scalar")
            return None
        if not isinstance(parsed, str):
            errors.append(f"{source}:{line}: scalar must be a string")
            return None
        return parsed
    if value[0] in "'[{|>" or value.lower() in {"true", "false", "null", "~"}:
        errors.append(f"{source}:{line}: value must be a plain or double-quoted string")
        return None
    return value


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
    if not description or len(description) > 1024 or "<" in description or ">" in description:
        errors.append(f"{skill_file}: description must be 1-1024 characters without angle brackets")
    if not body:
        errors.append(f"{skill_file}: body must not be empty")
    return body


def _validate_openai_yaml(skill_dir: Path, errors: list[str]) -> None:
    path = skill_dir / "agents" / "openai.yaml"
    if path.is_symlink():
        errors.append(f"{path}: openai.yaml must not be a link")
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
    if not values["display_name"] or len(values["display_name"]) > 64:
        errors.append(f"{path}: display_name must be 1-64 characters")
    if not 25 <= len(values["short_description"]) <= 64:
        errors.append(f"{path}: short_description must be 25-64 characters")
    if f"${skill_dir.name}" not in values["default_prompt"]:
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

    for directory in ("references", "assets", "scripts"):
        root = skill_dir / directory
        if not root.exists():
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
    reference_root = skill_dir / "references"
    if reference_root.is_dir():
        for path in sorted(reference_root.rglob("*.md")):
            try:
                documents.append((path, path.read_text(encoding="utf-8")))
            except (OSError, UnicodeError) as exc:
                errors.append(f"{path}: cannot read UTF-8 text: {exc}")
    for source, text in documents:
        for raw in MARKDOWN_LINK_PATTERN.findall(text):
            target_text = raw.strip("<>").split("#", 1)[0]
            if not target_text:
                continue
            scheme = re.match(r"^([A-Za-z][A-Za-z0-9+.-]*):", target_text)
            if scheme:
                if scheme.group(1).lower() in {"http", "https", "mailto"}:
                    continue
                errors.append(f"{source}: local link scheme is not allowed: {raw}")
                continue
            relative = PurePosixPath(target_text)
            target = source.parent.joinpath(*relative.parts)
            if relative.is_absolute() or ".." in relative.parts or not _contained(target, skill_dir):
                errors.append(f"{source}: local link escapes the Skill: {raw}")
            elif not target.exists():
                errors.append(f"{source}: local link does not exist: {raw}")


def _load_jsonl(path: Path, errors: list[str]) -> list[tuple[int, dict[str, Any]]]:
    records: list[tuple[int, dict[str, Any]]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        errors.append(f"{path}: cannot read UTF-8 text: {exc}")
        return records
    for number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except (ValueError, RecursionError) as exc:
            message = exc.msg if isinstance(exc, json.JSONDecodeError) else str(exc)
            errors.append(f"{path}:{number}: invalid JSON: {message}")
            continue
        if not isinstance(record, dict):
            errors.append(f"{path}:{number}: each JSONL record must be an object")
            continue
        records.append((number, record))
    return records


def _validate_evals(skills_root: Path, skill_names: set[str], errors: list[str]) -> None:
    seen_ids: dict[str, str] = {}
    for skill_name in sorted(skill_names):
        eval_dir = skills_root / skill_name / "evals"
        for kind in ("activation", "behavior"):
            path = eval_dir / f"{kind}.jsonl"
            if not path.is_file():
                errors.append(f"{path}: missing eval data")
                continue
            for number, record in _load_jsonl(path, errors):
                location = f"{path}:{number}"
                case_id = record.get("id")
                prompt = record.get("prompt")
                if not isinstance(case_id, str) or not case_id.strip():
                    errors.append(f"{location}: id must be a non-empty string")
                elif case_id in seen_ids:
                    errors.append(f"{location}: duplicate id {case_id!r}; first seen at {seen_ids[case_id]}")
                else:
                    seen_ids[case_id] = location
                if not isinstance(prompt, str) or not prompt.strip():
                    errors.append(f"{location}: prompt must be a non-empty string")

                if kind == "activation":
                    expected = record.get("expectedSkills")
                    if not isinstance(expected, list) or any(not isinstance(item, str) for item in expected):
                        errors.append(f"{location}: expectedSkills must be a list of Skill names")
                    elif len(expected) > 1 or len(expected) != len(set(expected)) or not set(expected) <= skill_names:
                        errors.append(f"{location}: expectedSkills must contain zero or one known Primary Skill")
                else:
                    referenced_skill = record.get("skill")
                    if referenced_skill not in skill_names:
                        errors.append(f"{location}: behavior case references unknown Skill {referenced_skill!r}")
                    rubric = record.get("expectedBehavior")
                    if (
                        not isinstance(rubric, list)
                        or not rubric
                        or any(not isinstance(item, str) or not item.strip() for item in rubric)
                    ):
                        errors.append(f"{location}: expectedBehavior must be a non-empty list of criteria")

                fixture = record.get("fixture")
                if fixture is not None:
                    if not isinstance(fixture, str) or not fixture:
                        errors.append(f"{location}: fixture must be a non-empty relative path")
                        continue
                    relative = PurePosixPath(fixture)
                    target = eval_dir.joinpath(*relative.parts)
                    if relative.is_absolute() or ".." in relative.parts or not _contained(target, eval_dir):
                        errors.append(f"{location}: fixture path escapes the eval directory")
                    elif target.is_symlink():
                        errors.append(f"{location}: fixture must not be a link")
                    elif not target.exists():
                        errors.append(f"{location}: fixture does not exist: {fixture}")


def validate_repository(root: Path) -> list[str]:
    errors: list[str] = []
    skills_root = root / "skills"
    manifest_path = skills_root / "plugins.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        plugins = manifest["plugins"]
        included = plugins[0]["skills"]["include"]
    except (OSError, UnicodeError, ValueError, RecursionError, KeyError, IndexError, TypeError) as exc:
        return [f"{manifest_path}: invalid plugin manifest: {exc}"]

    if manifest.get("schemaVersion") != 1 or not isinstance(plugins, list) or len(plugins) != 1:
        errors.append(f"{manifest_path}: expected schemaVersion 1 and exactly one plugin")
        included = []
    if not isinstance(included, list) or any(not isinstance(item, str) for item in included):
        errors.append(f"{manifest_path}: skills.include must be a list of Skill names")
        included_names: set[str] = set()
    else:
        included_names = set(included)
        if len(included_names) != len(included):
            errors.append(f"{manifest_path}: skills.include must not contain duplicates")

    actual_names = {
        path.name for path in skills_root.iterdir() if path.is_dir() and (path / "SKILL.md").exists()
    }
    if included_names != actual_names or actual_names != EXPECTED_SKILLS:
        errors.append(
            f"{manifest_path}: included, installed, and expected Skills must match: "
            f"{', '.join(sorted(EXPECTED_SKILLS))}"
        )

    for skill_name in sorted(EXPECTED_SKILLS):
        skill_dir = skills_root / skill_name
        body = _validate_skill_file(skill_dir, errors)
        _validate_openai_yaml(skill_dir, errors)
        _validate_resources(skill_dir, body, errors)
    _validate_evals(skills_root, EXPECTED_SKILLS, errors)
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
    print("Wow Skills validation passed: 4 Skills; activation and behavior eval data are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
