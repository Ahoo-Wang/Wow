"""Repository-level Wow Skill validation orchestration."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .core import Validation
from .evals import (
    load_skill_evals,
    validate_activation_cases,
    validate_behavior_cases,
    validate_unique_eval_ids,
)
from .package import (
    validate_inventory,
    validate_plugin_manifest,
    validate_skill,
)
from .trace_schema import validate_trace_schema

__all__ = ["PackageInventory", "validate_repository"]


@dataclass(frozen=True)
class PackageInventory:
    included: tuple[str, ...]
    skill_dirs: tuple[Path, ...]
    activation_cases: tuple[dict[str, Any], ...]
    behavior_cases: tuple[dict[str, Any], ...]


def validate_repository(
    repo_root: Path, validation: Validation
) -> PackageInventory:
    """Validate one repository and retain its parsed eval inventory."""
    validate_trace_schema(repo_root, validation)
    skills_root = repo_root / "skills"
    included = validate_plugin_manifest(skills_root, validation)
    skill_dirs = validate_inventory(skills_root, included, validation)
    activation_cases: list[dict[str, Any]] = []
    behavior_cases: list[dict[str, Any]] = []
    eval_ids: set[str] = set()
    for skill_dir in skill_dirs:
        validate_skill(skill_dir, validation)

    for skill_dir in skill_dirs:
        activation, behavior = load_skill_evals(skill_dir, validation)
        validate_unique_eval_ids(activation + behavior, validation, eval_ids)
        activation_cases.extend(activation)
        behavior_cases.extend(behavior)
    skill_names = set(included)
    validate_activation_cases(activation_cases, skill_names, validation)
    validate_behavior_cases(behavior_cases, skill_names, validation)
    return PackageInventory(
        included=tuple(included),
        skill_dirs=tuple(skill_dirs),
        activation_cases=tuple(activation_cases),
        behavior_cases=tuple(behavior_cases),
    )
