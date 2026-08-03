#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections.abc import Iterable
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
OPENAI_INTERFACE_KEYS = {
    "display_name",
    "short_description",
    "default_prompt",
}
ASSERTION_TYPES = {
    "sandbox.noExternalMutation",
    "sandbox.noExternalRead",
    "workspace.clean",
    "trace.command",
    "trace.notCommand",
    "trace.read",
    "trace.notRead",
    "output.regex",
    "output.notRegex",
    "process.exitCode",
}
FIXTURE_KINDS = {"isolated-git-worktree", "copied-directory"}
FIXTURE_REQUIRED_KEYS = {
    "fixtureId",
    "kind",
    "repository",
    "revision",
    "setup",
    "initialState",
}
FIXTURE_OPTIONAL_KEYS = {"baseRevision"}
BACKTICK_RESOURCE = re.compile(
    r"`(?P<path>(?:references|assets|scripts)/[^`\s]+)`"
)
MARKDOWN_LINK = re.compile(r"\[[^\]]*]\((?P<path>[^)]+)\)")


class Validation:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate the repository-owned Wow Agent Skills package."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Wow repository root (default: inferred from this script).",
    )
    parser.add_argument(
        "--quick-validator",
        type=Path,
        help="Path to skill-creator/scripts/quick_validate.py.",
    )
    parser.add_argument(
        "--skip-quick-validator",
        action="store_true",
        help="Skip the external standard validator; intended only for validator unit tests.",
    )
    return parser.parse_args()


def read_json(path: Path, validation: Validation) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        validation.error(f"{path}: invalid JSON: {error}")
        return None


def read_jsonl(path: Path, validation: Validation) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        validation.error(f"{path}: cannot read: {error}")
        return cases

    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            validation.error(f"{path}:{line_number}: invalid JSON: {error}")
            continue
        if not isinstance(value, dict):
            validation.error(f"{path}:{line_number}: expected a JSON object")
            continue
        value["__source__"] = f"{path}:{line_number}"
        cases.append(value)
    return cases


def require_string(
    value: dict[str, Any], key: str, source: str, validation: Validation
) -> str | None:
    item = value.get(key)
    if not isinstance(item, str) or not item.strip():
        validation.error(f"{source}: {key} must be a non-empty string")
        return None
    return item


def parse_strict_scalar(
    raw_value: str,
    source: str,
    validation: Validation,
    *,
    require_quoted: bool,
) -> str | None:
    value = raw_value.strip()
    if not value:
        validation.error(f"{source}: value must be a non-empty string")
        return None
    if value.startswith('"'):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError as error:
            validation.error(f"{source}: invalid quoted string: {error}")
            return None
        if not isinstance(parsed, str) or not parsed:
            validation.error(f"{source}: value must be a non-empty string")
            return None
        return parsed
    if require_quoted:
        validation.error(f"{source}: value must be double-quoted")
        return None
    return value


def parse_flat_mapping(
    lines: list[str],
    source: Path,
    validation: Validation,
    *,
    indent: str = "",
    require_quoted: bool = False,
) -> dict[str, str] | None:
    result: dict[str, str] = {}
    valid = True
    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        if not line.startswith(indent) or line[len(indent) :].startswith((" ", "\t")):
            validation.error(
                f"{source}:{line_number}: expected a flat mapping at indent "
                f"{len(indent)}"
            )
            valid = False
            continue
        content = line[len(indent) :]
        if ":" not in content:
            validation.error(f"{source}:{line_number}: expected key: value")
            valid = False
            continue
        key, raw_value = content.split(":", 1)
        key = key.strip()
        if not key or key in result:
            validation.error(f"{source}:{line_number}: invalid or duplicate key {key!r}")
            valid = False
            continue
        value = parse_strict_scalar(
            raw_value,
            f"{source}:{line_number}",
            validation,
            require_quoted=require_quoted,
        )
        if value is None:
            valid = False
            continue
        result[key] = value
    return result if valid else None


def resolve_quick_validator(explicit: Path | None) -> Path | None:
    candidates: list[Path] = []
    if explicit is not None:
        candidates.append(explicit)
    configured = os.environ.get("SKILL_VALIDATOR")
    if configured:
        candidates.append(Path(configured))
    codex_home = os.environ.get("CODEX_HOME")
    if codex_home:
        candidates.append(
            Path(codex_home)
            / "skills/.system/skill-creator/scripts/quick_validate.py"
        )
    candidates.append(
        Path.home()
        / ".codex/skills/.system/skill-creator/scripts/quick_validate.py"
    )
    return next((candidate for candidate in candidates if candidate.is_file()), None)


def validate_plugin_manifest(
    skills_root: Path, validation: Validation
) -> tuple[list[str], dict[str, Any] | None]:
    manifest = read_json(skills_root / "plugins.json", validation)
    if not isinstance(manifest, dict):
        return [], None
    if set(manifest) != {"schemaVersion", "plugins"}:
        validation.error(
            "skills/plugins.json: root keys must be schemaVersion and plugins"
        )
    if manifest.get("schemaVersion") != SCHEMA_VERSION:
        validation.error("skills/plugins.json: schemaVersion must be 1")
    plugins = manifest.get("plugins")
    if not isinstance(plugins, list) or len(plugins) != 1:
        validation.error("skills/plugins.json: exactly one plugin is required")
        return [], manifest
    plugin = plugins[0]
    if not isinstance(plugin, dict):
        validation.error("skills/plugins.json: plugin must be an object")
        return [], manifest
    for key in ("name", "description"):
        if not isinstance(plugin.get(key), str) or not plugin[key].strip():
            validation.error(f"skills/plugins.json: plugin.{key} must be non-empty")
    if isinstance(plugin.get("name"), str) and "/" in plugin["name"]:
        validation.error("skills/plugins.json: plugin.name must not contain '/'")
    if "keywords" in plugin and (
        not isinstance(plugin["keywords"], list)
        or any(not isinstance(item, str) for item in plugin["keywords"])
    ):
        validation.error("skills/plugins.json: plugin.keywords must be a string array")
    if "category" in plugin and not isinstance(plugin["category"], str):
        validation.error("skills/plugins.json: plugin.category must be a string")
    for key in ("interface", "policy"):
        if key in plugin and not isinstance(plugin[key], dict):
            validation.error(f"skills/plugins.json: plugin.{key} must be an object")
    skills = plugin.get("skills")
    if isinstance(skills, dict) and not set(skills).issubset({"include", "exclude"}):
        validation.error(
            "skills/plugins.json: skills supports only include and exclude"
        )
    include = skills.get("include") if isinstance(skills, dict) else None
    if not isinstance(include, list) or not include:
        validation.error("skills/plugins.json: skills.include must be non-empty")
        return [], manifest
    if any(not isinstance(name, str) or not name for name in include):
        validation.error("skills/plugins.json: every included skill must be a name")
        return [], manifest
    if len(include) != len(set(include)):
        validation.error("skills/plugins.json: duplicate included skill")
    for name in include:
        if any(character in name for character in "*?[]"):
            validation.error(
                f"skills/plugins.json: use explicit names instead of pattern {name!r}"
            )
    return include, manifest


def parse_frontmatter(path: Path, validation: Validation) -> dict[str, Any] | None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        validation.error(f"{path}: cannot read: {error}")
        return None
    parts = text.split("---", 2)
    if len(parts) != 3 or parts[0].strip():
        validation.error(f"{path}: expected leading YAML frontmatter")
        return None
    return parse_flat_mapping(parts[1].splitlines(), path, validation)


def validate_openai_yaml(
    skill_dir: Path, skill_name: str, validation: Validation
) -> None:
    path = skill_dir / "agents/openai.yaml"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        validation.error(f"{path}: cannot read: {error}")
        return
    if not lines or lines[0] != "interface:":
        validation.error(f"{path}: expected only the top-level interface mapping")
        return
    interface = parse_flat_mapping(
        lines[1:], path, validation, indent="  ", require_quoted=True
    )
    if interface is None:
        return
    if set(interface) != OPENAI_INTERFACE_KEYS:
        validation.error(
            f"{path}: interface keys must be {sorted(OPENAI_INTERFACE_KEYS)}"
        )
        return
    for key in OPENAI_INTERFACE_KEYS:
        if not isinstance(interface[key], str) or not interface[key].strip():
            validation.error(f"{path}: interface.{key} must be a non-empty string")
    default_prompt = interface.get("default_prompt", "")
    if f"${skill_name}" not in default_prompt:
        validation.error(
            f"{path}: default_prompt must explicitly mention ${skill_name}"
        )


def validate_resource_links(skill_dir: Path, validation: Validation) -> None:
    referenced_from_entry: set[Path] = set()
    markdown_files = [skill_dir / "SKILL.md"]
    markdown_files.extend(sorted((skill_dir / "references").glob("*.md")))

    for source in markdown_files:
        if not source.is_file():
            continue
        text = source.read_text(encoding="utf-8")
        for match in BACKTICK_RESOURCE.finditer(text):
            target_text = match.group("path").rstrip(".,;:")
            target = skill_dir / target_text
            if not target.exists():
                validation.error(f"{source}: missing resource {target_text}")
            if source.name == "SKILL.md":
                referenced_from_entry.add(target.resolve())
        for match in MARKDOWN_LINK.finditer(text):
            target_text = match.group("path").split("#", 1)[0]
            if not target_text or "://" in target_text or target_text.startswith("#"):
                continue
            if "../" in target_text:
                validation.error(f"{source}: cross-skill link is not allowed: {target_text}")
                continue
            target = source.parent / target_text
            if not target.exists():
                validation.error(f"{source}: missing Markdown target {target_text}")
            elif source.name == "SKILL.md":
                referenced_from_entry.add(target.resolve())

    for folder in ("references", "assets", "scripts"):
        resource_dir = skill_dir / folder
        if not resource_dir.is_dir():
            continue
        for resource in sorted(path for path in resource_dir.rglob("*") if path.is_file()):
            if resource.resolve() not in referenced_from_entry:
                validation.error(
                    f"{resource}: runtime resource is not linked directly from SKILL.md"
                )


def validate_shell_scripts(skill_dir: Path, validation: Validation) -> None:
    scripts_dir = skill_dir / "scripts"
    if not scripts_dir.is_dir():
        return
    for script in sorted(scripts_dir.glob("*.sh")):
        syntax = subprocess.run(
            ["bash", "-n", str(script)],
            capture_output=True,
            text=True,
            check=False,
        )
        if syntax.returncode != 0:
            validation.error(f"{script}: bash -n failed: {syntax.stderr.strip()}")
            continue
        help_result = subprocess.run(
            [str(script), "--help"],
            capture_output=True,
            text=True,
            check=False,
        )
        if help_result.returncode != 0:
            validation.error(
                f"{script}: --help exited {help_result.returncode}: "
                f"{help_result.stderr.strip()}"
            )


def validate_skill(
    skill_dir: Path,
    quick_validator: Path | None,
    run_quick_validator: bool,
    validation: Validation,
) -> None:
    skill_name = skill_dir.name
    skill_file = skill_dir / "SKILL.md"
    metadata = parse_frontmatter(skill_file, validation)
    if metadata is not None:
        if set(metadata) != {"name", "description"}:
            validation.error(
                f"{skill_file}: frontmatter must contain only name and description"
            )
        if metadata.get("name") != skill_name:
            validation.error(
                f"{skill_file}: name {metadata.get('name')!r} does not match directory"
            )
        if not isinstance(metadata.get("description"), str) or not metadata[
            "description"
        ].strip():
            validation.error(f"{skill_file}: description must be a non-empty string")

    if run_quick_validator and quick_validator is not None:
        result = subprocess.run(
            [sys.executable, str(quick_validator), str(skill_dir)],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            details = (result.stdout + result.stderr).strip()
            validation.error(f"{skill_dir}: quick_validate.py failed: {details}")

    validate_openai_yaml(skill_dir, skill_name, validation)
    validate_resource_links(skill_dir, validation)
    validate_shell_scripts(skill_dir, validation)


def validate_activation_cases(
    cases: Iterable[dict[str, Any]],
    skill_names: set[str],
    validation: Validation,
) -> None:
    expected_coverage: set[str] = set()
    negative_count = 0
    conflict_count = 0
    total = 0
    for case in cases:
        total += 1
        source = str(case["__source__"])
        if case.get("schemaVersion") != SCHEMA_VERSION:
            validation.error(f"{source}: schemaVersion must be 1")
        prompt = require_string(case, "prompt", source, validation)
        expected = case.get("expectedSkills")
        if not isinstance(expected, list) or any(
            not isinstance(name, str) for name in expected
        ):
            validation.error(f"{source}: expectedSkills must be a string array")
            expected = []
        expected_set = set(expected)
        unknown = expected_set - skill_names
        if unknown:
            validation.error(f"{source}: unknown expected skills: {sorted(unknown)}")
        if len(expected) != len(expected_set):
            validation.error(f"{source}: expectedSkills contains duplicates")
        if len(expected) > 1:
            validation.error(
                f"{source}: expectedSkills must contain at most one Primary Skill"
            )
        expected_coverage.update(expected_set)
        if not expected:
            negative_count += 1
        tags = case.get("tags", [])
        if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
            validation.error(f"{source}: tags must be a string array")
            tags = []
        if "conflict" in tags:
            conflict_count += 1
        if prompt:
            if "$" in prompt:
                validation.error(f"{source}: activation prompt must not name a $skill")
            leaked = [name for name in skill_names if name.lower() in prompt.lower()]
            if leaked:
                validation.error(
                    f"{source}: activation prompt leaks skill names: {sorted(leaked)}"
                )
    if total < 14:
        validation.error(f"activation evals: expected at least 14 cases, found {total}")
    if negative_count < 3:
        validation.error(
            f"activation evals: expected at least 3 negative cases, found {negative_count}"
        )
    if conflict_count < 6:
        validation.error(
            f"activation evals: expected at least 6 conflict cases, found {conflict_count}"
        )
    missing = skill_names - expected_coverage
    if missing:
        validation.error(
            f"activation evals: no positive expected activation for {sorted(missing)}"
        )


def validate_behavior_cases(
    cases: Iterable[dict[str, Any]],
    skill_names: set[str],
    validation: Validation,
) -> None:
    coverage: set[str] = set()
    fixture_ids: set[str] = set()
    for case in cases:
        source = str(case["__source__"])
        if case.get("schemaVersion") != SCHEMA_VERSION:
            validation.error(f"{source}: schemaVersion must be 1")
        prompt = require_string(case, "prompt", source, validation)
        if prompt:
            if "$" in prompt:
                validation.error(f"{source}: behavior prompt must not name a $skill")
            leaked = [name for name in skill_names if name.lower() in prompt.lower()]
            if leaked:
                validation.error(
                    f"{source}: behavior prompt leaks skill names: {sorted(leaked)}"
                )
        skill = require_string(case, "skill", source, validation)
        if skill and skill not in skill_names:
            validation.error(f"{source}: unknown behavior skill {skill!r}")
        elif skill:
            coverage.add(skill)
        fixture = case.get("fixture")
        if not isinstance(fixture, dict):
            validation.error(f"{source}: fixture must be an object")
        else:
            fixture_keys = set(fixture)
            missing_fixture_keys = FIXTURE_REQUIRED_KEYS - fixture_keys
            unknown_fixture_keys = fixture_keys - (
                FIXTURE_REQUIRED_KEYS | FIXTURE_OPTIONAL_KEYS
            )
            if missing_fixture_keys:
                validation.error(
                    f"{source}: fixture missing keys {sorted(missing_fixture_keys)}"
                )
            if unknown_fixture_keys:
                validation.error(
                    f"{source}: fixture has unknown keys {sorted(unknown_fixture_keys)}"
                )
            fixture_id = fixture.get("fixtureId")
            if not isinstance(fixture_id, str) or not fixture_id:
                validation.error(f"{source}: fixture.fixtureId must be non-empty")
            elif fixture_id != case.get("id"):
                validation.error(f"{source}: fixture.fixtureId must equal the case id")
            elif fixture_id in fixture_ids:
                validation.error(f"{source}: duplicate fixtureId {fixture_id!r}")
            else:
                fixture_ids.add(fixture_id)
            fixture_kind = fixture.get("kind")
            if fixture_kind not in FIXTURE_KINDS:
                validation.error(
                    f"{source}: fixture.kind must be one of {sorted(FIXTURE_KINDS)}"
                )
            repository = fixture.get("repository")
            if not isinstance(repository, str) or not repository:
                validation.error(f"{source}: fixture.repository must be non-empty")
            revision = fixture.get("revision")
            if fixture_kind == "isolated-git-worktree":
                if repository != ".":
                    validation.error(
                        f"{source}: isolated worktree repository must be '.'"
                    )
                if revision != "EVAL_SUBJECT":
                    validation.error(
                        f"{source}: isolated worktree revision must be EVAL_SUBJECT"
                    )
            elif fixture_kind == "copied-directory":
                if revision != "CONTENT_SHA256":
                    validation.error(
                        f"{source}: copied-directory revision must be CONTENT_SHA256"
                    )
                evals_dir = Path(source.rsplit(":", 1)[0]).parent
                fixture_repository = evals_dir / str(repository)
                if not fixture_repository.is_dir():
                    validation.error(
                        f"{source}: copied fixture repository does not exist: {repository}"
                    )
            if fixture.get("initialState") != "clean":
                validation.error(f"{source}: fixture.initialState must be clean")
            setup = fixture.get("setup")
            if not isinstance(setup, str) or not setup:
                validation.error(f"{source}: fixture.setup must be non-empty")
            elif setup != "none":
                evals_dir = Path(source.rsplit(":", 1)[0]).parent
                setup_path = evals_dir / setup
                if not setup_path.is_file():
                    validation.error(
                        f"{source}: fixture setup does not exist: {setup}"
                    )
                elif setup_path.suffix == ".patch":
                    repo_root = next(
                        (
                            parent
                            for parent in setup_path.parents
                            if (parent / ".git").exists()
                        ),
                        None,
                    )
                    if repo_root is None:
                        validation.error(
                            f"{source}: cannot locate repository for setup patch"
                        )
                    else:
                        patch_check = subprocess.run(
                            [
                                "git",
                                "-C",
                                str(repo_root),
                                "apply",
                                "--check",
                                str(setup_path),
                            ],
                            capture_output=True,
                            text=True,
                            check=False,
                        )
                        if patch_check.returncode != 0:
                            validation.error(
                                f"{source}: fixture setup patch does not apply: "
                                f"{patch_check.stderr.strip()}"
                            )
            if "baseRevision" in fixture and fixture["baseRevision"] != "EVAL_BASE":
                validation.error(
                    f"{source}: fixture.baseRevision must be EVAL_BASE when present"
                )
            if skill == "wow-review" and fixture.get("baseRevision") != "EVAL_BASE":
                validation.error(
                    f"{source}: wow-review behavior fixtures require EVAL_BASE"
                )
        assertions = case.get("assertions")
        if not isinstance(assertions, list) or not assertions:
            validation.error(f"{source}: assertions must be a non-empty array")
            continue
        has_negative = False
        for index, assertion in enumerate(assertions):
            assertion_source = f"{source}:assertions[{index}]"
            if not isinstance(assertion, dict):
                validation.error(f"{assertion_source}: expected an object")
                continue
            assertion_type = assertion.get("type")
            if assertion_type not in ASSERTION_TYPES:
                validation.error(
                    f"{assertion_source}: unknown assertion type {assertion_type!r}"
                )
                continue
            if assertion_type == "workspace.clean":
                if not isinstance(assertion.get("value"), bool):
                    validation.error(
                        f"{assertion_source}: workspace.clean requires boolean value"
                    )
                if assertion.get("value") is True:
                    has_negative = True
            elif assertion_type in {
                "sandbox.noExternalMutation",
                "sandbox.noExternalRead",
            }:
                if assertion.get("value") is not True:
                    validation.error(
                        f"{assertion_source}: {assertion_type} requires value true"
                    )
                has_negative = True
            elif assertion_type == "process.exitCode":
                if not isinstance(assertion.get("value"), int):
                    validation.error(
                        f"{assertion_source}: process.exitCode requires integer value"
                    )
            else:
                pattern = assertion.get("pattern")
                if not isinstance(pattern, str) or not pattern:
                    validation.error(
                        f"{assertion_source}: {assertion_type} requires pattern"
                    )
                else:
                    try:
                        re.compile(pattern)
                    except re.error as error:
                        validation.error(
                            f"{assertion_source}: invalid regex {pattern!r}: {error}"
                        )
                if assertion_type in {
                    "output.notRegex",
                    "trace.notCommand",
                    "trace.notRead",
                }:
                    has_negative = True
        if not has_negative:
            validation.warn(f"{source}: behavior case has no negative assertion")
    missing = skill_names - coverage
    if missing:
        validation.error(f"behavior evals: no cases for {sorted(missing)}")


def validate_evals(
    skill_dirs: list[Path], skill_names: set[str], validation: Validation
) -> tuple[int, int]:
    activation_cases: list[dict[str, Any]] = []
    behavior_cases: list[dict[str, Any]] = []
    ids: set[str] = set()

    for skill_dir in skill_dirs:
        evals_dir = skill_dir / "evals"
        activation_path = evals_dir / "activation.jsonl"
        behavior_path = evals_dir / "behavior.jsonl"
        if not activation_path.is_file() or not behavior_path.is_file():
            validation.error(
                f"{skill_dir}: expected evals/activation.jsonl and evals/behavior.jsonl"
            )
            continue
        current_activation = read_jsonl(activation_path, validation)
        current_behavior = read_jsonl(behavior_path, validation)
        for case in current_activation + current_behavior:
            source = str(case["__source__"])
            case_id = require_string(case, "id", source, validation)
            if case_id:
                if case_id in ids:
                    validation.error(f"{source}: duplicate eval id {case_id!r}")
                ids.add(case_id)
        activation_cases.extend(current_activation)
        behavior_cases.extend(current_behavior)

    validate_activation_cases(activation_cases, skill_names, validation)
    validate_behavior_cases(behavior_cases, skill_names, validation)
    return len(activation_cases), len(behavior_cases)


def main() -> int:
    args = parse_args()
    repo_root = args.repo_root.resolve()
    skills_root = repo_root / "skills"
    validation = Validation()

    included, _ = validate_plugin_manifest(skills_root, validation)
    actual_dirs = sorted(
        path.name for path in skills_root.iterdir() if path.is_dir()
    )
    if actual_dirs != sorted(included):
        validation.error(
            "skills/plugins.json include must exactly match skills/* directories: "
            f"included={sorted(included)}, actual={actual_dirs}"
        )

    quick_validator = None
    if not args.skip_quick_validator:
        quick_validator = resolve_quick_validator(args.quick_validator)
        if quick_validator is None:
            validation.error(
                "skill-creator quick_validate.py not found; pass --quick-validator or "
                "set SKILL_VALIDATOR"
            )

    skill_dirs = [skills_root / name for name in included]
    for skill_dir in skill_dirs:
        if not skill_dir.is_dir():
            validation.error(f"{skill_dir}: included skill directory is missing")
            continue
        validate_skill(
            skill_dir,
            quick_validator,
            not args.skip_quick_validator,
            validation,
        )

    activation_count, behavior_count = validate_evals(
        [path for path in skill_dirs if path.is_dir()],
        set(included),
        validation,
    )

    for warning in validation.warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for error in validation.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if validation.errors:
        print(f"Validation failed with {len(validation.errors)} error(s).")
        return 1

    print(
        f"Validated {len(skill_dirs)} skills, {activation_count} activation cases, "
        f"and {behavior_count} behavior cases."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
