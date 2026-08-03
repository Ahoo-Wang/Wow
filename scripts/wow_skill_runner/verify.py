"""Prepared-run evidence verification orchestration."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .assertions import verify_activation_case, verify_behavior_case
from .evidence import normalize_event_paths, validate_evidence_header
from .io import read_object, resolve_descriptor_path
from .model import AdapterTrust, EvalError, RUN_SCHEMA_VERSION
from .state import load_run

__all__ = [
    'verify_run',
]


def verify_run(
    repo_root: Path,
    run_dir: Path,
    evidence_path: Path,
    trust: AdapterTrust | None = None,
) -> dict[str, Any]:
    marker = load_run(run_dir, trust=trust)
    request = marker.pop("_sealedRequest")
    case = marker.pop("_sealedCase")
    resolved_run = resolve_descriptor_path(str(run_dir), "eval run directory")
    trusted_marker = dict(marker)
    trusted_marker["runDir"] = str(resolved_run)
    trusted_marker["sourceRepo"] = request["sourceRepo"]
    trusted_marker["pluginStaging"] = request["pluginSource"]
    trusted_marker["workspace"] = request.get("workspace")
    trusted_marker["fixture"] = request.get("revision")
    expected_repo = resolve_descriptor_path(str(repo_root), "source repository path")
    if resolve_descriptor_path(request["sourceRepo"], "prepared sourceRepo") != expected_repo:
        raise EvalError("prepared source repository does not match --repo-root")
    plugin_root = resolve_descriptor_path(request["pluginSource"], "prepared pluginSource")
    evidence = read_object(
        resolve_descriptor_path(str(evidence_path), "adapter evidence path"),
        "adapter evidence",
    )
    validate_evidence_header(evidence, trusted_marker, case, trust)
    raw_events = evidence.get("events")
    if not isinstance(raw_events, list):
        raise EvalError("evidence.events must be an array")
    workspace = Path(request["workspace"]) if request.get("workspace") else None
    events = normalize_event_paths(raw_events, workspace, plugin_root)
    if case["__suite__"] == "activation":
        failures = verify_activation_case(case, evidence, events)
    else:
        failures = verify_behavior_case(case, trusted_marker, evidence, events)
    return {
        "schemaVersion": RUN_SCHEMA_VERSION,
        "runId": request["runId"],
        "caseId": request["caseId"],
        "status": "FAIL" if failures else "PASS",
        "failures": failures,
        "adapter": evidence["adapter"],
    }
