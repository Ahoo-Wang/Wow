"""Wow Skill validator package primitives."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from .core import (
    BACKTICK_RESOURCE,
    MARKDOWN_LINK,
    MAX_SKILL_DESCRIPTION_LENGTH,
    MAX_SKILL_NAME_LENGTH,
    OPENAI_INTERFACE_KEYS,
    PLUGIN_SCHEMA_VERSION,
    SKILL_NAME,
    Validation,
    lexical_relative_path,
    parse_flat_mapping,
    path_uses_symlink,
    read_json,
    validate_bash_script,
)

__all__ = [
    "parse_frontmatter",
    "validate_skill_metadata",
    "validate_plugin_text_fields",
    "validate_plugin_keywords",
    "validate_plugin_optional_objects",
    "validate_plugin_fields",
    "validate_plugin_skills",
    "validate_plugin_manifest",
    "validate_openai_yaml",
    "resolve_markdown_target",
    "validate_resource_links",
    "validate_markdown_file",
    "validate_linked_resources",
    "validate_shell_scripts",
    "validate_shell_script",
    "validate_skill",
    "validate_inventory",
]


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
    if (
        type(manifest.get("schemaVersion")) is not int
        or manifest.get("schemaVersion") != PLUGIN_SCHEMA_VERSION
    ):
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


def validate_skill(skill_dir: Path, validation: Validation) -> None:
    skill_name = skill_dir.name
    skill_file = skill_dir / "SKILL.md"
    metadata = parse_frontmatter(skill_file, validation)
    if metadata is not None:
        validate_skill_metadata(metadata, skill_name, skill_file, validation)
    validate_openai_yaml(skill_dir, skill_name, validation)
    validate_resource_links(skill_dir, validation)
    validate_shell_scripts(skill_dir, validation)


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
