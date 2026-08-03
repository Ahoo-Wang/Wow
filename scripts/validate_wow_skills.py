#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

if __package__:
    from . import wow_skill_validator as _validator
else:
    import wow_skill_validator as _validator

for _exported_name in _validator.__all__:
    globals()[_exported_name] = getattr(_validator, _exported_name)
del _exported_name

__all__ = [*_validator.__all__, "parse_args", "main"]


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


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    repo_root = args.repo_root.resolve()
    validation = Validation()
    inventory = validate_repository(repo_root, validation)
    for warning in validation.warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for error in validation.errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if validation.errors:
        print(f"Validation failed with {len(validation.errors)} error(s).")
        return 1
    print(
        f"Validated {len(inventory.skill_dirs)} skills, "
        f"{len(inventory.activation_cases)} activation cases, "
        f"and {len(inventory.behavior_cases)} behavior contracts."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
