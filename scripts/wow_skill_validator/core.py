"""Wow Skill validator core primitives."""
from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess  # nosec B404 - syntax-only bash validation.
from pathlib import Path
from typing import Any

if __package__ and "." in __package__:
    from ..wow_skill_eval_contract import (
        TRACE_SCHEMA_RELATIVE_PATH,
        TRACE_SCHEMA_VERSION,
    )
else:
    from wow_skill_eval_contract import (
        TRACE_SCHEMA_RELATIVE_PATH,
        TRACE_SCHEMA_VERSION,
    )

__all__ = [
    "PLUGIN_SCHEMA_VERSION",
    "ACTIVATION_SCHEMA_VERSION",
    "BEHAVIOR_SCHEMA_VERSION",
    "TRACE_SCHEMA_VERSION",
    "TRACE_SCHEMA_RELATIVE_PATH",
    "MAX_SKILL_NAME_LENGTH",
    "MAX_SKILL_DESCRIPTION_LENGTH",
    "OPENAI_INTERFACE_KEYS",
    "ACTIVATION_KEYS",
    "BEHAVIOR_KEYS",
    "FIXTURE_REQUIRED_KEYS",
    "FIXTURE_OPTIONAL_KEYS",
    "FIXTURE_KINDS",
    "BEHAVIOR_MODES",
    "BOOLEAN_ASSERTIONS",
    "PATTERN_ASSERTIONS",
    "INTEGER_ASSERTIONS",
    "ASSERTION_TYPES",
    "BACKTICK_RESOURCE",
    "MARKDOWN_LINK",
    "SKILL_NAME",
    "BASH_EXECUTABLE",
    "Validation",
    "read_json",
    "require_string",
    "validate_exact_keys",
    "parse_strict_scalar",
    "parse_flat_mapping",
    "lexical_relative_path",
    "path_uses_symlink",
    "resolve_contained_path",
    "validate_no_symlinks",
    "validate_bash_script",
]

PLUGIN_SCHEMA_VERSION = 1

ACTIVATION_SCHEMA_VERSION = 1

BEHAVIOR_SCHEMA_VERSION = 2

MAX_SKILL_NAME_LENGTH = 64

MAX_SKILL_DESCRIPTION_LENGTH = 1024

OPENAI_INTERFACE_KEYS = {
    "display_name",
    "short_description",
    "default_prompt",
}

ACTIVATION_KEYS = {
    "schemaVersion",
    "id",
    "prompt",
    "expectedSkills",
    "tags",
}

BEHAVIOR_KEYS = {
    "schemaVersion",
    "id",
    "skill",
    "mode",
    "fixture",
    "prompt",
    "assertions",
    "tags",
}

FIXTURE_REQUIRED_KEYS = {
    "fixtureId",
    "kind",
    "repository",
    "revision",
    "setup",
    "initialState",
    "writeAllow",
}

FIXTURE_OPTIONAL_KEYS = {"baseRevision"}

FIXTURE_KINDS = {
    "isolated-git-worktree",
    "isolated-git-clone",
    "copied-directory",
}

BEHAVIOR_MODES = {"read-only", "mutating"}

BOOLEAN_ASSERTIONS = {
    "activation.primarySkill",
    "workspace.unchanged",
    "diff.nonEmpty",
    "oracle.cartCapacityBranches",
    "sandbox.noExternalRead",
    "sandbox.noExternalMutation",
    "trace.reviewedAllChangedFiles",
    "trace.reviewedChangedFile",
}

PATTERN_ASSERTIONS = {
    "artifact.changed",
    "diff.regex",
    "trace.read",
    "trace.notRead",
    "trace.write",
    "trace.notWrite",
    "output.regex",
    "output.notRegex",
}

INTEGER_ASSERTIONS = {"process.exitCode"}

ASSERTION_TYPES = (
    BOOLEAN_ASSERTIONS
    | PATTERN_ASSERTIONS
    | INTEGER_ASSERTIONS
    | {"command.exit", "trace.order"}
)

BACKTICK_RESOURCE = re.compile(
    r"`(?P<path>(?:references|assets|scripts)/[^`\s]+)`"
)

MARKDOWN_LINK = re.compile(r"\[[^\]]*]\((?P<path>[^)]+)\)")

SKILL_NAME = re.compile(r"^[a-z0-9-]+$")

BASH_EXECUTABLE = shutil.which("bash")


class Validation:
    """Collect deterministic package validation errors and warnings."""

    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)


def read_json(path: Path, validation: Validation) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        validation.error(f"{path}: invalid JSON: {error}")
        return None


def require_string(
    value: dict[str, Any], key: str, source: str, validation: Validation
) -> str | None:
    item = value.get(key)
    if not isinstance(item, str) or not item.strip():
        validation.error(f"{source}: {key} must be a non-empty string")
        return None
    return item


def validate_exact_keys(
    value: dict[str, Any], allowed: set[str], source: str, validation: Validation
) -> None:
    actual = set(value) - {"__source__"}
    if actual != allowed:
        missing = sorted(allowed - actual)
        unknown = sorted(actual - allowed)
        if missing:
            validation.error(f"{source}: missing keys {missing}")
        if unknown:
            validation.error(f"{source}: unknown keys {unknown}")


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
        parsed = parse_strict_scalar(
            raw_value,
            f"{source}:{line_number}",
            validation,
            require_quoted=require_quoted,
        )
        if parsed is None:
            valid = False
            continue
        result[key] = parsed
    return result if valid else None


def lexical_relative_path(raw: str) -> Path | None:
    path = Path(raw)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        return None
    return path


def path_uses_symlink(base: Path, relative: Path) -> bool:
    current = base
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            return True
    return False


def resolve_contained_path(
    base: Path,
    raw: str,
    source: str,
    validation: Validation,
    *,
    expected: str,
) -> Path | None:
    relative = lexical_relative_path(raw)
    if relative is None:
        validation.error(f"{source}: path must be a contained relative path: {raw!r}")
        return None
    if path_uses_symlink(base, relative):
        validation.error(f"{source}: symlink path is not allowed: {raw!r}")
        return None
    candidate = base / relative
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(base.resolve(strict=True))
    except (FileNotFoundError, OSError, ValueError):
        validation.error(f"{source}: path is missing or escapes its root: {raw!r}")
        return None
    if expected == "file" and not resolved.is_file():
        validation.error(f"{source}: expected a regular file: {raw!r}")
        return None
    if expected == "directory" and not resolved.is_dir():
        validation.error(f"{source}: expected a directory: {raw!r}")
        return None
    return resolved


def validate_no_symlinks(root: Path, source: str, validation: Validation) -> None:
    for path in root.rglob("*"):
        if path.is_symlink():
            validation.error(f"{source}: fixture must not contain symlink {path}")
        elif path.is_file() and path.stat().st_nlink > 1:
            validation.error(f"{source}: fixture must not contain hard link {path}")
        elif path.is_file() and path.suffix == ".sh":
            validate_bash_script(path, validation, require_help=False)


def validate_bash_script(
    script: Path, validation: Validation, *, require_help: bool
) -> None:
    try:
        text = script.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        validation.error(f"{script}: cannot read shell script: {error}")
        return
    if not text.startswith("#!/usr/bin/env bash\n"):
        validation.error(f"{script}: expected #!/usr/bin/env bash shebang")
    if "set -euo pipefail" not in text:
        validation.error(f"{script}: expected set -euo pipefail")
    if require_help and "--help" not in text:
        validation.error(f"{script}: expected a documented --help option")
    if not script.stat().st_mode & stat.S_IXUSR:
        validation.error(f"{script}: script must be executable")
    environment = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "LC_ALL": "C",
    }
    if BASH_EXECUTABLE is None:
        validation.error(f"{script}: bash executable is not available on PATH")
        return
    try:
        syntax = subprocess.run(  # nosec B603
            [BASH_EXECUTABLE, "--noprofile", "--norc", "-n", "--", str(script)],
            capture_output=True,
            text=True,
            check=False,
            stdin=subprocess.DEVNULL,
            timeout=10,
            env=environment,
        )
    except subprocess.TimeoutExpired:
        validation.error(f"{script}: bash syntax validation timed out")
        return
    if syntax.returncode != 0:
        validation.error(f"{script}: bash -n failed: {syntax.stderr.strip()}")
