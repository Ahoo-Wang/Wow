#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import stat
import subprocess  # nosec B404 - only syntax-only bash validation is executed.
import sys
from collections.abc import Iterable, Sequence
from pathlib import Path
from typing import Any


PLUGIN_SCHEMA_VERSION = 1
ACTIVATION_SCHEMA_VERSION = 1
BEHAVIOR_SCHEMA_VERSION = 2
TRACE_SCHEMA_VERSION = 2
TRACE_SCHEMA_RELATIVE_PATH = Path(
    "scripts/schemas/wow-skill-eval-trace.schema.json"
)
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


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate the repository-owned Wow Agent Skills package."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Wow repository root (default: inferred from this script).",
    )
    return parser.parse_args(argv)


def read_json(path: Path, validation: Validation) -> Any | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        validation.error(f"{path}: invalid JSON: {error}")
        return None


def validate_trace_schema(repo_root: Path, validation: Validation) -> None:
    source = repo_root / TRACE_SCHEMA_RELATIVE_PATH
    schema = read_json(source, validation)
    if schema is None:
        return
    if not isinstance(schema, dict):
        validation.error(f"{source}: trace schema must be a JSON object")
        return

    def require(condition: bool, contract: str) -> None:
        if not condition:
            validation.error(f"{source}: trace schema must define {contract}")

    def mapping(value: Any) -> dict[str, Any]:
        return value if isinstance(value, dict) else {}

    def exact_string_set(value: Any, expected: set[str]) -> bool:
        return (
            isinstance(value, list)
            and all(isinstance(item, str) for item in value)
            and set(value) == expected
        )

    root_required = {
        "schemaVersion",
        "runId",
        "requestSha256",
        "adapter",
        "output",
        "processExitCode",
        "events",
        "sandbox",
        "attestation",
    }
    require(
        schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema",
        "JSON Schema draft 2020-12",
    )
    require(schema.get("type") == "object", "an object root")
    require(schema.get("additionalProperties") is False, "a closed object root")
    require(
        exact_string_set(schema.get("required"), root_required),
        "the exact root fields",
    )
    properties = schema.get("properties")
    require(isinstance(properties, dict), "root properties")
    if isinstance(properties, dict):
        require(set(properties) == root_required, "only the exact root properties")
        require(
            properties.get("schemaVersion") == {"const": TRACE_SCHEMA_VERSION},
            f"schemaVersion {TRACE_SCHEMA_VERSION}",
        )
        require(
            properties.get("requestSha256")
            == {
                "type": "string",
                "description": (
                    "SHA-256 of the exact UTF-8 file bytes stored in request.json, "
                    "including formatting whitespace and the trailing newline."
                ),
                "pattern": "^[0-9a-f]{64}$",
            },
            "a lowercase SHA-256 request hash",
        )
        adapter = properties.get("adapter")
        expected_capabilities = {
            "activation-trace",
            "tool-trace",
            "command-policy",
            "workspace-policy",
            "activation-only",
        }
        if isinstance(adapter, dict):
            adapter_properties = adapter.get("properties")
            require(
                exact_string_set(
                    adapter.get("required"),
                    {"name", "version", "capabilities", "freshTask", "promptExact"},
                ),
                "the exact adapter fields",
            )
            require(adapter.get("additionalProperties") is False, "a closed adapter")
            require(isinstance(adapter_properties, dict), "adapter properties")
            if isinstance(adapter_properties, dict):
                capabilities = mapping(adapter_properties.get("capabilities"))
                capability_items = mapping(capabilities.get("items"))
                require(
                    capabilities.get("uniqueItems") is True
                    and exact_string_set(
                        capability_items.get("enum"), expected_capabilities
                    ),
                    "the exact adapter capabilities",
                )
                require(
                    adapter_properties.get("freshTask") == {"const": True}
                    and adapter_properties.get("promptExact") == {"const": True},
                    "freshTask and promptExact attestations",
                )
        else:
            require(False, "an adapter object")
        events = properties.get("events")
        event_items = mapping(mapping(events).get("items"))
        require(
            isinstance(events, dict)
            and events.get("type") == "array"
            and event_items.get("oneOf")
            == [
                {"$ref": "#/$defs/activationEvent"},
                {"$ref": "#/$defs/accessEvent"},
                {"$ref": "#/$defs/commandEvent"},
            ],
            "the activation, access, and command event union",
        )

    definitions = schema.get("$defs")
    expected_definitions = {
        "activationEvent",
        "accessEvent",
        "commandEvent",
        "sandbox",
        "attestation",
    }
    require(isinstance(definitions, dict), "event and attestation definitions")
    if not isinstance(definitions, dict):
        return
    require(set(definitions) == expected_definitions, "only the exact definitions")

    expected_required = {
        "activationEvent": {"seq", "type", "skill", "primary"},
        "accessEvent": {"seq", "type", "path"},
        "commandEvent": {"seq", "type", "argv", "cwd", "executable", "exitCode"},
        "sandbox": {
            "externalReadBlocked",
            "externalMutationBlocked",
            "networkBlocked",
            "connectorsBlocked",
            "evalContentBlocked",
            "activationOnly",
            "commandPolicyEnforced",
        },
        "attestation": {"algorithm", "keyId", "signature"},
    }
    for name, required_fields in expected_required.items():
        definition = definitions.get(name)
        require(isinstance(definition, dict), f"a {name} object")
        if not isinstance(definition, dict):
            continue
        require(definition.get("type") == "object", f"{name} as an object")
        require(definition.get("additionalProperties") is False, f"a closed {name}")
        require(
            exact_string_set(definition.get("required"), required_fields),
            f"the exact {name} fields",
        )
        require(
            isinstance(definition.get("properties"), dict)
            and set(definition["properties"]) == required_fields,
            f"only the exact {name} properties",
        )

    activation_properties = mapping(
        mapping(definitions.get("activationEvent")).get("properties")
    )
    access_properties = mapping(
        mapping(definitions.get("accessEvent")).get("properties")
    )
    command_properties = mapping(
        mapping(definitions.get("commandEvent")).get("properties")
    )
    attestation_properties = mapping(
        mapping(definitions.get("attestation")).get("properties")
    )
    require(
        activation_properties.get("skill") == {"type": "string", "minLength": 1},
        "a non-empty activation skill",
    )
    require(
        access_properties.get("path") == {"type": "string", "minLength": 1},
        "a non-empty access path",
    )
    require(
        mapping(command_properties.get("argv")).get("minItems") == 1
        and mapping(command_properties.get("argv")).get("items")
        == {"type": "string", "minLength": 1},
        "a non-empty command argv",
    )
    require(
        command_properties.get("executable") == {"type": "string", "pattern": "^/"},
        "an absolute command executable",
    )
    require(
        attestation_properties.get("algorithm") == {"const": "hmac-sha256"}
        and attestation_properties.get("signature")
        == {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "an HMAC-SHA256 attestation",
    )


def read_jsonl(path: Path, validation: Validation) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        validation.error(f"{path}: cannot read: {error}")
        return cases

    for line_number, line in enumerate(lines, start=1):
        if not line.strip():
            continue
        source = f"{path}:{line_number}"
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            validation.error(f"{source}: invalid JSON: {error}")
            continue
        if not isinstance(value, dict):
            validation.error(f"{source}: expected a JSON object")
            continue
        value["__source__"] = source
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


def parse_frontmatter(path: Path, validation: Validation) -> dict[str, str] | None:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        validation.error(f"{path}: cannot read: {error}")
        return None
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        validation.error(f"{path}: expected leading YAML frontmatter")
        return None
    try:
        closing_delimiter = lines.index("---", 1)
    except ValueError:
        validation.error(f"{path}: expected a standalone closing frontmatter delimiter")
        return None
    return parse_flat_mapping(
        lines[1:closing_delimiter], path, validation, require_quoted=True
    )


def validate_skill_metadata(
    metadata: dict[str, str], skill_name: str, source: Path, validation: Validation
) -> None:
    if set(metadata) != {"name", "description"}:
        validation.error(f"{source}: frontmatter must contain only name and description")
        return
    name = metadata["name"].strip()
    description = metadata["description"].strip()
    if name != skill_name:
        validation.error(f"{source}: name {name!r} does not match directory")
    if not SKILL_NAME.fullmatch(name):
        validation.error(f"{source}: name must use lowercase hyphen-case")
    if name.startswith("-") or name.endswith("-") or "--" in name:
        validation.error(f"{source}: name has an invalid hyphen placement")
    if len(name) > MAX_SKILL_NAME_LENGTH:
        validation.error(
            f"{source}: name exceeds {MAX_SKILL_NAME_LENGTH} characters"
        )
    if not description:
        validation.error(f"{source}: description must be non-empty")
    if len(description) > MAX_SKILL_DESCRIPTION_LENGTH:
        validation.error(
            f"{source}: description exceeds {MAX_SKILL_DESCRIPTION_LENGTH} characters"
        )
    if "<" in description or ">" in description:
        validation.error(f"{source}: description must not contain angle brackets")


def validate_plugin_text_fields(
    plugin: dict[str, Any], validation: Validation
) -> None:
    for key in ("name", "description"):
        if not isinstance(plugin.get(key), str) or not plugin[key].strip():
            validation.error(f"skills/plugins.json: plugin.{key} must be non-empty")
    if isinstance(plugin.get("name"), str) and "/" in plugin["name"]:
        validation.error("skills/plugins.json: plugin.name must not contain '/'")


def validate_plugin_keywords(plugin: dict[str, Any], validation: Validation) -> None:
    keywords = plugin.get("keywords")
    if keywords is not None and (
        not isinstance(keywords, list)
        or any(not isinstance(item, str) for item in keywords)
    ):
        validation.error("skills/plugins.json: plugin.keywords must be a string array")


def validate_plugin_optional_objects(
    plugin: dict[str, Any], validation: Validation
) -> None:
    if "category" in plugin and not isinstance(plugin["category"], str):
        validation.error("skills/plugins.json: plugin.category must be a string")
    for key in ("interface", "policy"):
        if key in plugin and not isinstance(plugin[key], dict):
            validation.error(f"skills/plugins.json: plugin.{key} must be an object")


def validate_plugin_fields(plugin: dict[str, Any], validation: Validation) -> None:
    validate_plugin_text_fields(plugin, validation)
    validate_plugin_keywords(plugin, validation)
    validate_plugin_optional_objects(plugin, validation)


def validate_plugin_skills(plugin: dict[str, Any], validation: Validation) -> list[str]:
    skills = plugin.get("skills")
    if not isinstance(skills, dict):
        validation.error("skills/plugins.json: plugin.skills must be an object")
        return []
    if set(skills) != {"include"}:
        validation.error("skills/plugins.json: skills must contain only explicit include")
    include = skills.get("include")
    if not isinstance(include, list) or not include:
        validation.error("skills/plugins.json: skills.include must be non-empty")
        return []
    if any(not isinstance(name, str) or not name for name in include):
        validation.error("skills/plugins.json: every included skill must be a name")
        return []
    if len(include) != len(set(include)):
        validation.error("skills/plugins.json: duplicate included skill")
    for name in include:
        if not SKILL_NAME.fullmatch(name):
            validation.error(f"skills/plugins.json: invalid skill name {name!r}")
    return include


def validate_plugin_manifest(skills_root: Path, validation: Validation) -> list[str]:
    manifest = read_json(skills_root / "plugins.json", validation)
    if not isinstance(manifest, dict):
        return []
    if set(manifest) != {"schemaVersion", "plugins"}:
        validation.error(
            "skills/plugins.json: root keys must be schemaVersion and plugins"
        )
    if type(manifest.get("schemaVersion")) is not int or manifest.get("schemaVersion") != PLUGIN_SCHEMA_VERSION:
        validation.error("skills/plugins.json: schemaVersion must be 1")
    plugins = manifest.get("plugins")
    if not isinstance(plugins, list) or len(plugins) != 1:
        validation.error("skills/plugins.json: exactly one plugin is required")
        return []
    plugin = plugins[0]
    if not isinstance(plugin, dict):
        validation.error("skills/plugins.json: plugin must be an object")
        return []
    validate_plugin_fields(plugin, validation)
    return validate_plugin_skills(plugin, validation)


def validate_openai_yaml(
    skill_dir: Path, skill_name: str, validation: Validation
) -> None:
    path = skill_dir / "agents/openai.yaml"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
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
    if f"${skill_name}" not in interface.get("default_prompt", ""):
        validation.error(
            f"{path}: default_prompt must explicitly mention ${skill_name}"
        )


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


def resolve_markdown_target(
    skill_dir: Path,
    base: Path,
    raw: str,
    source: Path,
    validation: Validation,
) -> Path | None:
    relative = lexical_relative_path(raw)
    if relative is None or path_uses_symlink(base, relative):
        validation.error(f"{source}: local link escapes the Skill: {raw}")
        return None
    candidate = base / relative
    try:
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(skill_dir.resolve(strict=True))
    except (FileNotFoundError, OSError, ValueError):
        validation.error(f"{source}: missing or external local target {raw}")
        return None
    return resolved


def validate_resource_links(skill_dir: Path, validation: Validation) -> None:
    referenced_from_entry: set[Path] = set()
    markdown_files = [skill_dir / "SKILL.md"]
    markdown_files.extend(sorted((skill_dir / "references").glob("*.md")))
    for source in markdown_files:
        if source.is_file():
            validate_markdown_file(
                skill_dir, source, referenced_from_entry, validation
            )
    validate_linked_resources(skill_dir, referenced_from_entry, validation)


def validate_markdown_file(
    skill_dir: Path,
    source: Path,
    referenced_from_entry: set[Path],
    validation: Validation,
) -> None:
    try:
        text = source.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        validation.error(f"{source}: cannot read markdown: {error}")
        return
    for match in BACKTICK_RESOURCE.finditer(text):
        raw = match.group("path").rstrip(".,;:")
        target = resolve_markdown_target(
            skill_dir, skill_dir, raw, source, validation
        )
        if target is not None and source.name == "SKILL.md":
            referenced_from_entry.add(target)
    for match in MARKDOWN_LINK.finditer(text):
        raw = match.group("path").split("#", 1)[0]
        if not raw or "://" in raw or raw.startswith("#"):
            continue
        target = resolve_markdown_target(
            skill_dir, source.parent, raw, source, validation
        )
        if target is not None and source.name == "SKILL.md":
            referenced_from_entry.add(target)


def validate_linked_resources(
    skill_dir: Path, referenced_from_entry: set[Path], validation: Validation
) -> None:
    for folder in ("references", "assets", "scripts"):
        resource_dir = skill_dir / folder
        if not resource_dir.is_dir():
            continue
        for resource in sorted(resource_dir.rglob("*")):
            if resource.is_symlink():
                validation.error(f"{resource}: runtime resource must not be a symlink")
            elif resource.is_file() and resource.resolve() not in referenced_from_entry:
                validation.error(
                    f"{resource}: runtime resource is not linked directly from SKILL.md"
                )


def validate_shell_scripts(skill_dir: Path, validation: Validation) -> None:
    scripts_dir = skill_dir / "scripts"
    if not scripts_dir.is_dir():
        return
    for script in sorted(scripts_dir.glob("*.sh")):
        validate_shell_script(script, validation)


def validate_shell_script(script: Path, validation: Validation) -> None:
    validate_bash_script(script, validation, require_help=True)


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


def validate_skill(skill_dir: Path, validation: Validation) -> None:
    skill_name = skill_dir.name
    skill_file = skill_dir / "SKILL.md"
    metadata = parse_frontmatter(skill_file, validation)
    if metadata is not None:
        validate_skill_metadata(metadata, skill_name, skill_file, validation)
    validate_openai_yaml(skill_dir, skill_name, validation)
    validate_resource_links(skill_dir, validation)
    validate_shell_scripts(skill_dir, validation)


def validate_tags(case: dict[str, Any], source: str, validation: Validation) -> list[str]:
    tags = case.get("tags")
    if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
        validation.error(f"{source}: tags must be a string array")
        return []
    return tags


def validate_activation_case(
    case: dict[str, Any], skill_names: set[str], validation: Validation
) -> tuple[set[str], bool, bool]:
    source = str(case["__source__"])
    validate_exact_keys(case, ACTIVATION_KEYS, source, validation)
    if type(case.get("schemaVersion")) is not int or case.get("schemaVersion") != ACTIVATION_SCHEMA_VERSION:
        validation.error(f"{source}: schemaVersion must be 1")
    prompt = require_string(case, "prompt", source, validation)
    expected = case.get("expectedSkills")
    if not isinstance(expected, list) or any(
        not isinstance(name, str) for name in expected
    ):
        validation.error(f"{source}: expectedSkills must be a string array")
        expected = []
    expected_set = set(expected)
    if expected_set - skill_names:
        validation.error(
            f"{source}: unknown expected skills: {sorted(expected_set - skill_names)}"
        )
    if len(expected) != len(expected_set):
        validation.error(f"{source}: expectedSkills contains duplicates")
    if len(expected) > 1:
        validation.error(
            f"{source}: expectedSkills must contain at most one Primary Skill"
        )
    tags = validate_tags(case, source, validation)
    if prompt:
        validate_prompt_does_not_leak(prompt, source, skill_names, validation)
        if "language-en" in tags and not prompt.isascii():
            validation.error(
                f"{source}: language-en activation prompt must contain only ASCII text"
            )
    return expected_set, not expected, "conflict" in tags


def validate_prompt_does_not_leak(
    prompt: str, source: str, skill_names: set[str], validation: Validation
) -> None:
    if "$" in prompt:
        validation.error(f"{source}: eval prompt must not name a $skill")
    leaked = [name for name in skill_names if name.lower() in prompt.lower()]
    if leaked:
        validation.error(f"{source}: eval prompt leaks skill names: {sorted(leaked)}")


def validate_activation_cases(
    cases: Iterable[dict[str, Any]], skill_names: set[str], validation: Validation
) -> None:
    coverage: set[str] = set()
    negative_count = 0
    conflict_count = 0
    english_count = 0
    scenario_tags: set[str] = set()
    total = 0
    for case in cases:
        total += 1
        expected, negative, conflict = validate_activation_case(
            case, skill_names, validation
        )
        coverage.update(expected)
        negative_count += int(negative)
        conflict_count += int(conflict)
        tags = case.get("tags")
        if isinstance(tags, list):
            scenario_tags.update(tag for tag in tags if isinstance(tag, str))
            english_count += int("language-en" in tags)
    if total < 24:
        validation.error(f"activation evals: expected at least 24 cases, found {total}")
    if negative_count < 5:
        validation.error(
            f"activation evals: expected at least 5 negative cases, found {negative_count}"
        )
    if conflict_count < 10:
        validation.error(
            f"activation evals: expected at least 10 conflict cases, found {conflict_count}"
        )
    if english_count < 5:
        validation.error(
            f"activation evals: expected at least 5 English cases, found {english_count}"
        )
    required_scenarios = {
        "review-data-cutover",
        "debug-data-cutover",
        "breaking-no-data",
    }
    missing_scenarios = required_scenarios - scenario_tags
    if missing_scenarios:
        validation.error(
            "activation evals: missing boundary scenarios "
            f"{sorted(missing_scenarios)}"
        )
    missing = skill_names - coverage
    if missing:
        validation.error(
            f"activation evals: no positive expected activation for {sorted(missing)}"
        )


def validate_patch_path(raw: str, source: Path, validation: Validation) -> None:
    if not raw.startswith(("a/", "b/")):
        validation.error(f"{source}: patch path must start with a/ or b/: {raw!r}")
        return
    relative = lexical_relative_path(raw[2:])
    if relative is None or relative.parts[0] == ".git":
        validation.error(f"{source}: unsafe patch path {raw!r}")


def validate_patch_file(path: Path, validation: Validation) -> None:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error:
        validation.error(f"{path}: cannot read setup patch: {error}")
        return
    headers = 0
    hunks = 0
    for line in lines:
        if line.startswith("diff --git "):
            try:
                tokens = shlex.split(line)
            except ValueError as error:
                validation.error(f"{path}: invalid patch header: {error}")
                continue
            if len(tokens) != 4:
                validation.error(f"{path}: invalid diff --git header")
                continue
            validate_patch_path(tokens[2], path, validation)
            validate_patch_path(tokens[3], path, validation)
            headers += 1
        elif line.startswith("@@ "):
            hunks += 1
        elif line in {"new file mode 120000", "new mode 120000"}:
            validation.error(f"{path}: setup patch must not create symlinks")
    if headers == 0 or hunks == 0:
        validation.error(f"{path}: setup must be a unified git patch with a hunk")


def validate_write_allow(
    value: Any, mode: str | None, source: str, validation: Validation
) -> list[str]:
    if not isinstance(value, list) or any(
        not isinstance(pattern, str) or not pattern for pattern in value
    ):
        validation.error(f"{source}: fixture.writeAllow must be a string array")
        return []
    if mode == "read-only" and value:
        validation.error(f"{source}: read-only fixture.writeAllow must be empty")
    if mode == "mutating" and not value:
        validation.error(f"{source}: mutating fixture.writeAllow must be non-empty")
    for pattern in value:
        if Path(pattern).is_absolute() or ".." in Path(pattern).parts:
            validation.error(f"{source}: unsafe writeAllow pattern {pattern!r}")
    return value


def validate_fixture_location(
    fixture: dict[str, Any], evals_dir: Path, source: str, validation: Validation
) -> None:
    kind = fixture.get("kind")
    repository = fixture.get("repository")
    revision = fixture.get("revision")
    fixtures_root = evals_dir / "fixtures"
    if isinstance(kind, str) and kind in {
        "isolated-git-worktree",
        "isolated-git-clone",
    }:
        if repository != ".":
            validation.error(f"{source}: isolated Git repository must be '.'")
        if revision != "EVAL_SUBJECT":
            validation.error(
                f"{source}: isolated Git revision must be EVAL_SUBJECT"
            )
    elif kind == "copied-directory":
        if revision != "CONTENT_SHA256":
            validation.error(
                f"{source}: copied-directory revision must be CONTENT_SHA256"
            )
        if not isinstance(repository, str) or not repository:
            validation.error(
                f"{source}: copied-directory repository must be a non-empty string"
            )
            return
        resolved = resolve_contained_path(
            fixtures_root,
            repository,
            f"{source}:fixture.repository",
            validation,
            expected="directory",
        )
        if resolved is not None:
            validate_no_symlinks(resolved, source, validation)


def validate_fixture_setup(
    fixture: dict[str, Any], evals_dir: Path, source: str, validation: Validation
) -> None:
    setup = fixture.get("setup")
    if not isinstance(setup, str) or not setup:
        validation.error(f"{source}: fixture.setup must be non-empty")
        return
    if setup == "none":
        return
    if fixture.get("kind") == "copied-directory":
        validation.error(f"{source}: copied-directory fixture.setup must be none")
        return
    setup_path = resolve_contained_path(
        evals_dir / "fixtures",
        setup,
        f"{source}:fixture.setup",
        validation,
        expected="file",
    )
    if setup_path is None:
        return
    if setup_path.suffix != ".patch":
        validation.error(f"{source}: fixture.setup must be none or a .patch file")
        return
    validate_patch_file(setup_path, validation)


def validate_fixture(
    fixture: Any,
    case_id: str | None,
    skill: str | None,
    mode: str | None,
    source: str,
    validation: Validation,
) -> None:
    if not isinstance(fixture, dict):
        validation.error(f"{source}: fixture must be an object")
        return
    actual = set(fixture)
    allowed = FIXTURE_REQUIRED_KEYS | FIXTURE_OPTIONAL_KEYS
    if actual - allowed:
        validation.error(f"{source}: fixture has unknown keys {sorted(actual - allowed)}")
    if FIXTURE_REQUIRED_KEYS - actual:
        validation.error(
            f"{source}: fixture missing keys {sorted(FIXTURE_REQUIRED_KEYS - actual)}"
        )
    fixture_id = fixture.get("fixtureId")
    if fixture_id != case_id:
        validation.error(f"{source}: fixture.fixtureId must equal the case id")
    kind = fixture.get("kind")
    if not isinstance(kind, str) or kind not in FIXTURE_KINDS:
        validation.error(f"{source}: fixture.kind must be one of {sorted(FIXTURE_KINDS)}")
    if fixture.get("initialState") != "clean":
        validation.error(f"{source}: fixture.initialState must be clean")
    evals_dir = Path(source.rsplit(":", 1)[0]).parent
    validate_fixture_location(fixture, evals_dir, source, validation)
    validate_fixture_setup(fixture, evals_dir, source, validation)
    validate_write_allow(fixture.get("writeAllow"), mode, source, validation)
    base_revision = fixture.get("baseRevision")
    if base_revision is not None and (
        not isinstance(base_revision, str)
        or base_revision not in {"EVAL_BASE", "EVAL_SUBJECT"}
    ):
        validation.error(
            f"{source}: fixture.baseRevision must be EVAL_BASE or EVAL_SUBJECT"
        )
    if skill == "wow-review" and base_revision is None:
        validation.error(f"{source}: wow-review fixtures require a baseRevision")


def validate_regex(pattern: Any, source: str, validation: Validation) -> None:
    if not isinstance(pattern, str) or not pattern:
        validation.error(f"{source}: pattern must be a non-empty string")
        return
    try:
        re.compile(pattern)
    except re.error as error:
        validation.error(f"{source}: invalid regex {pattern!r}: {error}")


def validate_command_exit(
    assertion: dict[str, Any], source: str, validation: Validation
) -> None:
    validate_exact_keys(assertion, {"type", "argv", "exitCode"}, source, validation)
    validate_command_argv(assertion.get("argv"), source, validation)
    exit_code = assertion.get("exitCode")
    if type(exit_code) is not int and exit_code != "nonzero":
        validation.error(f"{source}: exitCode must be an integer or 'nonzero'")


def validate_command_argv(value: Any, source: str, validation: Validation) -> None:
    if not isinstance(value, list) or not value or any(
        not isinstance(item, str) or not item for item in value
    ):
        validation.error(f"{source}: argv must be a non-empty string array")
        return
    if command_uses_shell_wrapper(value):
        validation.error(f"{source}: shell-wrapper command evidence is forbidden")


def command_uses_shell_wrapper(argv: list[str]) -> bool:
    shells = {"bash", "sh", "zsh", "cmd", "cmd.exe", "powershell", "pwsh"}
    executable_index = 0
    executable = Path(argv[0]).name.lower()
    if executable == "env":
        index = 1
        while index < len(argv):
            argument = argv[index]
            if argument == "--":
                index += 1
                break
            if argument == "-S" or argument == "--split-string":
                try:
                    split = shlex.split(" ".join(argv[index + 1 :]))
                except ValueError:
                    return True
                return any(Path(token).name.lower() in shells for token in split)
            if argument in {"-u", "--unset", "-C", "--chdir"}:
                index += 2
                continue
            if argument.startswith("-") or "=" in argument:
                index += 1
                continue
            break
        if index >= len(argv):
            return False
        executable_index = index
        executable = Path(argv[index]).name.lower()
    if executable not in shells:
        return False
    return any(
        argument.lower() in {"/c", "/k", "--command"}
        or (argument.startswith("-") and "c" in argument[1:].lower())
        for argument in argv[executable_index + 1 :]
    )


def validate_order_step(
    step: Any, source: str, validation: Validation
) -> dict[str, Any] | None:
    if not isinstance(step, dict):
        validation.error(f"{source}: order step must be an object")
        return None
    event = step.get("event")
    allowed = {"event", "pattern"}
    if event == "command":
        allowed = {"event", "argv", "exitCode"}
    validate_exact_keys(step, allowed, source, validation)
    if not isinstance(event, str) or event not in {"read", "write", "command"}:
        validation.error(f"{source}: event must be read, write, or command")
    if event == "command":
        validate_command_argv(step.get("argv"), source, validation)
        exit_code = step.get("exitCode")
        if type(exit_code) is not int and exit_code != "nonzero":
            validation.error(f"{source}: command exitCode must be integer or nonzero")
    else:
        validate_regex(step.get("pattern"), source, validation)
    return step


def validate_trace_order(
    assertion: dict[str, Any], source: str, validation: Validation
) -> list[dict[str, Any]]:
    validate_exact_keys(assertion, {"type", "events"}, source, validation)
    events = assertion.get("events")
    if not isinstance(events, list) or len(events) < 2:
        validation.error(f"{source}: trace.order requires at least two events")
        return []
    valid: list[dict[str, Any]] = []
    for index, step in enumerate(events):
        checked = validate_order_step(step, f"{source}:events[{index}]", validation)
        if checked is not None:
            valid.append(checked)
    return valid


def validate_assertion(
    assertion: Any, source: str, validation: Validation
) -> tuple[str | None, list[dict[str, Any]]]:
    if not isinstance(assertion, dict):
        validation.error(f"{source}: expected an object")
        return None, []
    assertion_type = assertion.get("type")
    if not isinstance(assertion_type, str) or assertion_type not in ASSERTION_TYPES:
        validation.error(f"{source}: unknown assertion type {assertion_type!r}")
        return None, []
    if assertion_type in BOOLEAN_ASSERTIONS:
        validate_exact_keys(assertion, {"type", "value"}, source, validation)
        if assertion.get("value") is not True:
            validation.error(f"{source}: {assertion_type} requires value true")
    elif assertion_type in PATTERN_ASSERTIONS:
        validate_exact_keys(assertion, {"type", "pattern"}, source, validation)
        validate_regex(assertion.get("pattern"), source, validation)
    elif assertion_type in INTEGER_ASSERTIONS:
        validate_exact_keys(assertion, {"type", "value"}, source, validation)
        if type(assertion.get("value")) is not int:
            validation.error(f"{source}: {assertion_type} requires an integer")
    elif assertion_type == "command.exit":
        validate_command_exit(assertion, source, validation)
    else:
        return assertion_type, validate_trace_order(assertion, source, validation)
    return assertion_type, []


def order_proven_red_green_argv(
    events: list[dict[str, Any]],
) -> set[tuple[str, ...]]:
    proven: set[tuple[str, ...]] = set()
    for failure_index, failure in enumerate(events):
        argv = failure.get("argv")
        if (
            failure.get("event") != "command"
            or failure.get("exitCode") != "nonzero"
            or not isinstance(argv, list)
            or any(not isinstance(argument, str) for argument in argv)
        ):
            continue
        for write_index in range(failure_index + 1, len(events)):
            if events[write_index].get("event") != "write":
                continue
            if any(
                success.get("event") == "command"
                and success.get("exitCode") == 0
                and success.get("argv") == argv
                for success in events[write_index + 1 :]
            ):
                proven.add(tuple(argv))
                break
    return proven


def validate_common_behavior_policy(
    mode: str | None,
    fixture: Any,
    assertion_types: set[str],
    source: str,
    validation: Validation,
) -> None:
    required = {"activation.primarySkill", "process.exitCode"}
    if not required.issubset(assertion_types):
        validation.error(f"{source}: assertions must include {sorted(required)}")
    if "sandbox.noExternalMutation" not in assertion_types:
        validation.error(f"{source}: behavior case requires sandbox.noExternalMutation")
    requires_external_read_block = mode == "read-only" or (
        isinstance(fixture, dict) and fixture.get("kind") == "copied-directory"
    )
    if requires_external_read_block and "sandbox.noExternalRead" not in assertion_types:
        validation.error(f"{source}: hermetic case requires sandbox.noExternalRead")


def validate_read_only_policy(
    assertion_types: set[str], source: str, validation: Validation
) -> None:
    if "workspace.unchanged" not in assertion_types:
        validation.error(f"{source}: read-only case requires workspace.unchanged")
    forbidden = {"diff.nonEmpty", "artifact.changed", "trace.write"}
    declared = forbidden & assertion_types
    if declared:
        validation.error(
            f"{source}: read-only case declares mutating assertions {sorted(declared)}"
        )


def validate_mutating_policy(
    assertions: list[dict[str, Any]],
    assertion_types: set[str],
    orders: list[list[dict[str, Any]]],
    source: str,
    validation: Validation,
) -> None:
    mutating_required = {"diff.nonEmpty", "artifact.changed", "command.exit", "trace.order"}
    if not mutating_required.issubset(assertion_types):
        validation.error(
            f"{source}: mutating case requires {sorted(mutating_required)}"
        )
    proven_commands: set[tuple[str, ...]] = set()
    for events in orders:
        proven_commands.update(order_proven_red_green_argv(events))
    if not proven_commands:
        validation.error(
            f"{source}: mutating case requires the same command argv for "
            "RED -> write -> GREEN trace.order"
        )
    successful_commands = {
        tuple(assertion["argv"])
        for assertion in assertions
        if isinstance(assertion, dict)
        and assertion.get("type") == "command.exit"
        and assertion.get("exitCode") == 0
        and isinstance(assertion.get("argv"), list)
        and all(isinstance(argument, str) for argument in assertion["argv"])
    }
    if not successful_commands:
        validation.error(f"{source}: mutating case requires a successful command.exit")
    elif proven_commands and not proven_commands & successful_commands:
        validation.error(
            f"{source}: successful command.exit must match the GREEN argv in "
            "RED -> write -> GREEN trace.order"
        )
    if "workspace.unchanged" in assertion_types:
        validation.error(f"{source}: mutating case must not require workspace.unchanged")


def validate_behavior_policy(
    mode: str | None,
    fixture: Any,
    assertions: list[dict[str, Any]],
    assertion_types: list[str],
    orders: list[list[dict[str, Any]]],
    source: str,
    validation: Validation,
) -> None:
    assertion_type_set = set(assertion_types)
    validate_common_behavior_policy(
        mode, fixture, assertion_type_set, source, validation
    )
    if mode == "read-only":
        validate_read_only_policy(assertion_type_set, source, validation)
    elif mode == "mutating":
        validate_mutating_policy(
            assertions, assertion_type_set, orders, source, validation
        )


def collect_behavior_assertions(
    assertions: list[Any], source: str, validation: Validation
) -> tuple[list[str], list[list[dict[str, Any]]]]:
    assertion_types: list[str] = []
    orders: list[list[dict[str, Any]]] = []
    for index, assertion in enumerate(assertions):
        assertion_type, order = validate_assertion(
            assertion, f"{source}:assertions[{index}]", validation
        )
        if assertion_type:
            assertion_types.append(assertion_type)
        if order:
            orders.append(order)
    return assertion_types, orders


def validate_zero_process_exit(
    assertions: list[Any], assertion_types: list[str], source: str, validation: Validation
) -> None:
    if "process.exitCode" not in assertion_types:
        return
    invalid = any(
        isinstance(assertion, dict)
        and assertion.get("type") == "process.exitCode"
        and assertion.get("value") != 0
        for assertion in assertions
    )
    if invalid:
        validation.error(f"{source}: process.exitCode must require zero")


def validate_behavior_case(
    case: dict[str, Any], skill_names: set[str], validation: Validation
) -> str | None:
    source = str(case["__source__"])
    validate_exact_keys(case, BEHAVIOR_KEYS, source, validation)
    if type(case.get("schemaVersion")) is not int or case.get("schemaVersion") != BEHAVIOR_SCHEMA_VERSION:
        validation.error(f"{source}: behavior schemaVersion must be 2")
    case_id = require_string(case, "id", source, validation)
    prompt = require_string(case, "prompt", source, validation)
    skill = require_string(case, "skill", source, validation)
    if skill and skill not in skill_names:
        validation.error(f"{source}: unknown behavior skill {skill!r}")
    mode = case.get("mode")
    if not isinstance(mode, str) or mode not in BEHAVIOR_MODES:
        validation.error(f"{source}: mode must be one of {sorted(BEHAVIOR_MODES)}")
        mode = None
    if prompt:
        validate_prompt_does_not_leak(prompt, source, skill_names, validation)
    validate_tags(case, source, validation)
    fixture = case.get("fixture")
    validate_fixture(fixture, case_id, skill, mode, source, validation)
    assertions = case.get("assertions")
    if not isinstance(assertions, list) or not assertions:
        validation.error(f"{source}: assertions must be a non-empty array")
        return skill
    assertion_types, orders = collect_behavior_assertions(
        assertions, source, validation
    )
    validate_behavior_policy(
        mode, fixture, assertions, assertion_types, orders, source, validation
    )
    validate_zero_process_exit(assertions, assertion_types, source, validation)
    return skill


def validate_behavior_cases(
    cases: Iterable[dict[str, Any]], skill_names: set[str], validation: Validation
) -> None:
    coverage: set[str] = set()
    fixture_ids: set[str] = set()
    for case in cases:
        skill = validate_behavior_case(case, skill_names, validation)
        if skill:
            coverage.add(skill)
        case_id = case.get("id")
        if isinstance(case_id, str):
            if case_id in fixture_ids:
                validation.error(f"{case['__source__']}: duplicate fixtureId {case_id!r}")
            fixture_ids.add(case_id)
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
        current_activation, current_behavior = load_skill_evals(skill_dir, validation)
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


def load_skill_evals(
    skill_dir: Path, validation: Validation
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    evals_dir = skill_dir / "evals"
    activation_path = evals_dir / "activation.jsonl"
    behavior_path = evals_dir / "behavior.jsonl"
    if not activation_path.is_file() or not behavior_path.is_file():
        validation.error(
            f"{skill_dir}: expected evals/activation.jsonl and evals/behavior.jsonl"
        )
        return [], []
    return read_jsonl(activation_path, validation), read_jsonl(
        behavior_path, validation
    )


def validate_inventory(
    skills_root: Path, included: list[str], validation: Validation
) -> list[Path]:
    try:
        actual_dirs = sorted(path.name for path in skills_root.iterdir() if path.is_dir())
    except OSError as error:
        validation.error(f"{skills_root}: cannot list skills: {error}")
        return []
    if actual_dirs != sorted(included):
        validation.error(
            "skills/plugins.json include must exactly match skills/* directories: "
            f"included={sorted(included)}, actual={actual_dirs}"
        )
    skill_dirs = [skills_root / name for name in included]
    for skill_dir in skill_dirs:
        if not skill_dir.is_dir():
            validation.error(f"{skill_dir}: included skill directory is missing")
    return [path for path in skill_dirs if path.is_dir()]


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    repo_root = args.repo_root.resolve()
    skills_root = repo_root / "skills"
    validation = Validation()
    validate_trace_schema(repo_root, validation)
    included = validate_plugin_manifest(skills_root, validation)
    skill_dirs = validate_inventory(skills_root, included, validation)
    for skill_dir in skill_dirs:
        validate_skill(skill_dir, validation)
    activation_count, behavior_count = validate_evals(
        skill_dirs, set(included), validation
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
        f"and {behavior_count} behavior contracts."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
