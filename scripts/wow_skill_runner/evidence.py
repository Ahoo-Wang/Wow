"""Adapter capability, trace schema, and path normalization checks."""

from __future__ import annotations

import fnmatch
from pathlib import Path
from typing import Any

from .model import (
    ACTIVATION_ONLY_CAPABILITY,
    ALLOWED_CAPABILITIES,
    AdapterTrust,
    BASE_CAPABILITIES,
    EVAL_READ_DENY,
    EVENT_TYPES,
    EvalError,
    SANDBOX_CAPABILITY,
    TRACE_SCHEMA_VERSION,
    UnsupportedEvidence,
)
from .security import validate_attestation

__all__ = [
    'validate_adapter_identity',
    'validate_adapter_attestations',
    'validate_adapter_capabilities',
    'validate_adapter',
    'validate_evidence_header',
    'validate_event_shape',
    'validate_activation_event',
    'validate_command_event',
    'normalize_event_paths',
    'normalize_access_path',
    'normalize_command_cwd',
    'normalize_command_executable',
]


def validate_adapter_identity(
    adapter: dict[str, Any], trust: AdapterTrust
) -> None:
    expected_keys = {"name", "version", "capabilities", "freshTask", "promptExact"}
    if set(adapter) != expected_keys:
        raise EvalError("evidence.adapter has unknown or missing keys")
    if not isinstance(adapter.get("name"), str) or not adapter["name"]:
        raise EvalError("evidence.adapter.name must be non-empty")
    if not isinstance(adapter.get("version"), str) or not adapter["version"]:
        raise EvalError("evidence.adapter.version must be pinned")
    if (
        adapter["name"] != trust.adapter_name
        or adapter["version"] != trust.adapter_version
    ):
        raise EvalError("evidence adapter identity does not match the trust key")


def validate_adapter_attestations(adapter: dict[str, Any]) -> None:
    if adapter.get("freshTask") is not True or adapter.get("promptExact") is not True:
        raise UnsupportedEvidence("adapter did not attest a fresh task and exact prompt")


def validate_adapter_capabilities(
    adapter: dict[str, Any], case: dict[str, Any]
) -> None:
    capabilities = adapter.get("capabilities")
    if not isinstance(capabilities, list) or any(
        not isinstance(item, str) for item in capabilities
    ):
        raise EvalError("evidence.adapter.capabilities must be a string array")
    if len(capabilities) != len(set(capabilities)):
        raise EvalError("evidence.adapter.capabilities must be unique")
    unknown = set(capabilities) - ALLOWED_CAPABILITIES
    if unknown:
        raise EvalError(f"evidence.adapter has unknown capabilities: {sorted(unknown)}")
    missing = BASE_CAPABILITIES - set(capabilities)
    if missing:
        raise UnsupportedEvidence(f"adapter lacks capabilities: {sorted(missing)}")
    requires_sandbox = case["__suite__"] == "activation" or any(
        assertion["type"].startswith("sandbox.") for assertion in case.get("assertions", [])
    )
    if requires_sandbox and SANDBOX_CAPABILITY not in capabilities:
        raise UnsupportedEvidence("adapter cannot enforce workspace policy")
    if (
        case["__suite__"] == "activation"
        and ACTIVATION_ONLY_CAPABILITY not in capabilities
    ):
        raise UnsupportedEvidence("adapter cannot stop after activation")
    if (
        case["__suite__"] == "behavior"
        and ACTIVATION_ONLY_CAPABILITY in capabilities
    ):
        raise EvalError("behavior evidence must not declare activation-only capability")


def validate_adapter(
    evidence: dict[str, Any], case: dict[str, Any], trust: AdapterTrust
) -> None:
    adapter = evidence.get("adapter")
    if not isinstance(adapter, dict):
        raise EvalError("evidence.adapter must be an object")
    validate_adapter_identity(adapter, trust)
    validate_adapter_attestations(adapter)
    validate_adapter_capabilities(adapter, case)


def validate_evidence_header(
    evidence: dict[str, Any],
    marker: dict[str, Any],
    case: dict[str, Any],
    trust: AdapterTrust | None,
) -> None:
    allowed = {
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
    required = allowed
    if set(evidence) - allowed or not required.issubset(evidence):
        raise EvalError("evidence has unknown or missing keys")
    if (
        type(evidence.get("schemaVersion")) is not int
        or evidence.get("schemaVersion") != TRACE_SCHEMA_VERSION
    ):
        raise EvalError("unsupported evidence schemaVersion")
    if evidence.get("runId") != marker["runId"]:
        raise EvalError("evidence runId does not match the prepared run")
    if evidence.get("requestSha256") != marker["requestSha256"]:
        raise EvalError("evidence requestSha256 does not match the prepared request")
    if not isinstance(evidence.get("output"), str):
        raise EvalError("evidence.output must be a string")
    if type(evidence.get("processExitCode")) is not int:
        raise EvalError("evidence.processExitCode must be an integer")
    validate_attestation(evidence, trust)
    assert trust is not None
    validate_adapter(evidence, case, trust)
    sandbox = evidence.get("sandbox")
    if not isinstance(sandbox, dict):
        raise UnsupportedEvidence("adapter did not provide sandbox enforcement evidence")
    expected_activation_only = case["__suite__"] == "activation"
    if sandbox.get("activationOnly") is not expected_activation_only:
        raise EvalError(
            "sandbox activationOnly does not match the prepared evaluation suite"
        )


def validate_event_shape(event: Any, previous: int) -> int:
    if not isinstance(event, dict):
        raise EvalError("every trace event must be an object")
    sequence = event.get("seq")
    event_type = event.get("type")
    if type(sequence) is not int or sequence <= previous:
        raise EvalError("trace event seq values must be unique and strictly increasing")
    if not isinstance(event_type, str) or event_type not in EVENT_TYPES:
        raise EvalError(f"unknown trace event type: {event_type!r}")
    if event_type == "activation":
        validate_activation_event(event)
    elif event_type in {"read", "write"}:
        if (
            set(event) != {"seq", "type", "path"}
            or not isinstance(event.get("path"), str)
            or not event["path"]
        ):
            raise EvalError(f"invalid {event_type} event")
    else:
        validate_command_event(event)
    return sequence


def validate_activation_event(event: dict[str, Any]) -> None:
    if set(event) != {"seq", "type", "skill", "primary"}:
        raise EvalError("invalid activation event keys")
    if (
        not isinstance(event.get("skill"), str)
        or not event["skill"]
        or type(event.get("primary")) is not bool
    ):
        raise EvalError("invalid activation event values")


def validate_command_event(event: dict[str, Any]) -> None:
    if set(event) != {"seq", "type", "argv", "cwd", "executable", "exitCode"}:
        raise EvalError("invalid command event keys")
    argv = event.get("argv")
    if not isinstance(argv, list) or not argv or any(
        not isinstance(item, str) or not item for item in argv
    ):
        raise EvalError("command argv must be a non-empty string array")
    if (
        not isinstance(event.get("cwd"), str)
        or not isinstance(event.get("executable"), str)
        or not event["executable"]
        or type(event.get("exitCode")) is not int
    ):
        raise EvalError("command cwd/executable/exitCode is invalid")


def normalize_event_paths(
    events: list[dict[str, Any]], workspace: Path | None, plugin_root: Path
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    previous = -1
    for original in events:
        previous = validate_event_shape(original, previous)
        event = dict(original)
        if event["type"] in {"read", "write"}:
            event["path"] = normalize_access_path(
                Path(event["path"]), event["type"], workspace, plugin_root
            )
        elif event["type"] == "command":
            event["cwd"] = normalize_command_cwd(Path(event["cwd"]), workspace)
            if event["cwd"] != ".":
                raise EvalError("command execution outside the fixture root is forbidden")
            event["executable"] = normalize_command_executable(
                Path(event["executable"]), event["argv"][0], workspace
            )
        normalized.append(event)
    return normalized


def normalize_access_path(
    path: Path, event_type: str, workspace: Path | None, plugin_root: Path
) -> str:
    if not path.is_absolute():
        if workspace is None:
            raise EvalError(f"relative {event_type} path without a workspace")
        path = workspace / path
    try:
        resolved = path.resolve(strict=False)
    except (OSError, ValueError, RuntimeError) as error:
        raise EvalError(f"invalid {event_type} path: {path!s}") from error
    roots = [("workspace", workspace), ("plugin", plugin_root)]
    for label, root in roots:
        if root is None:
            continue
        try:
            resolved_root = root.resolve()
        except (OSError, ValueError, RuntimeError) as error:
            raise EvalError(f"invalid allowed {label} root: {root!s}") from error
        try:
            relative = resolved.relative_to(resolved_root)
        except ValueError:
            continue
        relative_text = relative.as_posix()
        if label == "workspace" and any(
            fnmatch.fnmatchcase(relative_text, pattern) for pattern in EVAL_READ_DENY
        ):
            raise EvalError(f"{event_type} accessed hidden eval content: {relative_text}")
        if event_type == "write" and label != "workspace":
            raise EvalError(f"write escaped the fixture workspace: {resolved}")
        return f"{label}/{relative_text}"
    raise EvalError(f"{event_type} escaped allowed roots: {resolved}")


def normalize_command_cwd(path: Path, workspace: Path | None) -> str:
    if workspace is None:
        if path.as_posix() not in {"", "."}:
            raise EvalError("activation command cwd must be empty")
        return "."
    if not path.is_absolute():
        path = workspace / path
    try:
        resolved = path.resolve(strict=False)
        resolved_workspace = workspace.resolve()
    except (OSError, ValueError, RuntimeError) as error:
        raise EvalError(f"invalid command cwd: {path!s}") from error
    try:
        relative = resolved.relative_to(resolved_workspace)
    except ValueError as error:
        raise EvalError(f"command cwd escaped fixture workspace: {path}") from error
    return relative.as_posix() or "."


def normalize_command_executable(
    path: Path, argv0: str, workspace: Path | None
) -> str:
    if not path.is_absolute():
        raise EvalError("command executable attestation must be an absolute path")
    try:
        resolved = path.resolve(strict=False)
    except (OSError, ValueError, RuntimeError) as error:
        raise EvalError(f"invalid command executable: {path!s}") from error
    if argv0.startswith("./"):
        if workspace is None:
            raise EvalError("workspace-relative executable used without a workspace")
        try:
            resolved_workspace = workspace.resolve()
            expected = (workspace / argv0.removeprefix("./")).resolve(strict=False)
        except (OSError, ValueError, RuntimeError) as error:
            raise EvalError("invalid workspace-relative executable") from error
        if resolved != expected:
            raise EvalError("command executable does not match workspace-relative argv[0]")
        try:
            relative = expected.relative_to(resolved_workspace)
        except ValueError as error:
            raise EvalError("workspace-relative executable escaped the workspace") from error
        return f"workspace/{relative.as_posix()}"
    if Path(argv0).name != argv0 or path.name != argv0:
        raise EvalError("command executable does not match bare argv[0]")
    if workspace is not None:
        try:
            resolved_workspace = workspace.resolve()
        except (OSError, ValueError, RuntimeError) as error:
            raise EvalError(f"invalid workspace root: {workspace!s}") from error
        try:
            resolved.relative_to(resolved_workspace)
        except ValueError:
            pass
        else:
            raise EvalError("bare command executable must not resolve inside the workspace")
    return str(resolved)
