"""Shared schema identity for Wow Skill maintenance tooling."""

from pathlib import Path

__all__ = ["TRACE_SCHEMA_VERSION", "TRACE_SCHEMA_RELATIVE_PATH"]


TRACE_SCHEMA_VERSION = 2
TRACE_SCHEMA_RELATIVE_PATH = Path(
    "scripts/schemas/wow-skill-eval-trace.schema.json"
)
