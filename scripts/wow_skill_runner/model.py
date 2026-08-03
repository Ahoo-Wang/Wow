"""Shared contracts, constants, and value objects for eval runs."""

from __future__ import annotations

import shutil
from dataclasses import dataclass
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
    'RUN_SCHEMA_VERSION',
    'TRACE_SCHEMA_VERSION',
    'RUN_SEAL_DOMAIN',
    'RECOVERY_SEAL_DOMAIN',
    'RUN_MARKER',
    'REQUEST_FILE',
    'CONTRACT_FILE',
    'BASELINE_MANIFEST_FILE',
    'STAGED_PLUGIN_DIR',
    'ORACLE_RUNTIME_DIR',
    'RECOVERY_TOMBSTONE',
    'MAX_JSON_BYTES',
    'STAGED_TRACE_SCHEMA',
    'TRACE_SCHEMA_PATH',
    'COMMAND_POLICY_CAPABILITY',
    'BASE_CAPABILITIES',
    'SANDBOX_CAPABILITY',
    'ACTIVATION_ONLY_CAPABILITY',
    'ALLOWED_CAPABILITIES',
    'EVENT_TYPES',
    'GIT_EXECUTABLE',
    'EVAL_READ_DENY',
    'TOOL_WRITE_ALLOW',
    'TOOL_CONTROL_WRITE_DENY',
    'EvalError',
    'UnsupportedEvidence',
    'AdapterTrust',
    'AssertionContext',
]


RUN_SCHEMA_VERSION = 2
RUN_SEAL_DOMAIN = b"wow-skill-eval-run-v1\0"
RECOVERY_SEAL_DOMAIN = b"wow-skill-eval-recovery-v1\0"
RUN_MARKER = "run.json"
REQUEST_FILE = "request.json"
CONTRACT_FILE = "contract.json"
BASELINE_MANIFEST_FILE = "baseline-manifest.json"
STAGED_PLUGIN_DIR = "plugin"
ORACLE_RUNTIME_DIR = ".oracle-runtime"
RECOVERY_TOMBSTONE = "recovery-cleaned.json"
MAX_JSON_BYTES = 16 * 1024 * 1024
STAGED_TRACE_SCHEMA = TRACE_SCHEMA_RELATIVE_PATH.name
TRACE_SCHEMA_PATH = TRACE_SCHEMA_RELATIVE_PATH.as_posix()
COMMAND_POLICY_CAPABILITY = "command-policy"


BASE_CAPABILITIES = {
    "activation-trace",
    "tool-trace",
    COMMAND_POLICY_CAPABILITY,
}
SANDBOX_CAPABILITY = "workspace-policy"
ACTIVATION_ONLY_CAPABILITY = "activation-only"


ALLOWED_CAPABILITIES = BASE_CAPABILITIES | {
    SANDBOX_CAPABILITY,
    ACTIVATION_ONLY_CAPABILITY,
}
EVENT_TYPES = {"activation", "read", "write", "command"}
GIT_EXECUTABLE = shutil.which("git")


EVAL_READ_DENY = [
    "skills/*/evals",
    "skills/*/evals/**",
    "scripts/run_wow_skill_evals.py",
    "scripts/validate_wow_skills.py",
    "scripts/wow_skill_eval_contract.py",
    "scripts/wow_skill_runner",
    "scripts/wow_skill_runner/**",
    "scripts/wow_skill_validator",
    "scripts/wow_skill_validator/**",
    "scripts/test_run_wow_skill_evals.py",
    "scripts/test_validate_wow_skills.py",
]


TOOL_WRITE_ALLOW = [
    ".eval-runtime",
    ".eval-runtime/**",
    ".gradle",
    ".gradle/**",
    "**/.gradle",
    "**/.gradle/**",
    ".kotlin",
    ".kotlin/**",
    "**/.kotlin",
    "**/.kotlin/**",
    "build",
    "build/**",
    "**/build",
    "**/build/**",
]


TOOL_CONTROL_WRITE_DENY = [
    ".eval-runtime/**/init.gradle",
    ".eval-runtime/**/init.gradle.kts",
    ".eval-runtime/**/init.d/**",
    ".eval-runtime/**/gradle.properties",
    ".gradle/**/init.gradle",
    ".gradle/**/init.gradle.kts",
    ".gradle/**/init.d/**",
    ".gradle/**/gradle.properties",
]


class EvalError(RuntimeError):
    """Raised when fixture preparation or evidence validation cannot continue."""


class UnsupportedEvidence(EvalError):
    """Raised when an adapter cannot provide a required enforcement capability."""


@dataclass(frozen=True)
class AdapterTrust:
    key_id: str
    adapter_name: str
    adapter_version: str
    secret: bytes


@dataclass(frozen=True)
class AssertionContext:
    case: dict[str, Any]
    evidence: dict[str, Any]
    events: list[dict[str, Any]]
    paths: list[str]
    diff: str
    unchanged: bool
    marker: dict[str, Any]
