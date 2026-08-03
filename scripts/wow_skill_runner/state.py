"""Frozen package inventory and prepared-run state validation."""

from __future__ import annotations

import re
import shutil
from pathlib import Path
from typing import Any

if __package__ and "." in __package__:
    from ..wow_skill_validator.api import validate_repository
    from ..wow_skill_validator.core import Validation
else:
    from wow_skill_validator.api import validate_repository
    from wow_skill_validator.core import Validation

from .io import (
    hash_manifest,
    hash_tree,
    is_sha256,
    read_object,
    resolve_descriptor_path,
    sha256_bytes,
    sha256_file,
    sha256_regular_file,
    write_json,
)
from .model import (
    AdapterTrust,
    BASELINE_MANIFEST_FILE,
    CONTRACT_FILE,
    EVAL_READ_DENY,
    EvalError,
    MAX_JSON_BYTES,
    REQUEST_FILE,
    RUN_MARKER,
    RUN_SCHEMA_VERSION,
    STAGED_PLUGIN_DIR,
    STAGED_TRACE_SCHEMA,
    TOOL_CONTROL_WRITE_DENY,
    TOOL_WRITE_ALLOW,
    TRACE_SCHEMA_PATH,
    TRACE_SCHEMA_VERSION,
)
from .security import validate_run_seal

__all__ = [
    'load_cases',
    'stage_runtime_plugin',
    'write_contract',
    'behavior_workspace_policy',
    'activation_workspace_policy',
    'build_request',
    'fixture_source',
    'validate_run_marker_shape',
    'validate_revision_descriptor',
    'validate_prepared_request',
    'validate_prepared_artifacts',
    'validate_prepared_case_binding',
    'load_run',
]


def load_cases(repo_root: Path) -> dict[str, dict[str, Any]]:
    validation = Validation()
    inventory = validate_repository(repo_root, validation)
    if validation.errors:
        raise EvalError("package validation failed: " + "; ".join(validation.errors))
    cases: dict[str, dict[str, Any]] = {}
    for suite, suite_cases in (
        ("activation", inventory.activation_cases),
        ("behavior", inventory.behavior_cases),
    ):
        for case in suite_cases:
            case_copy = dict(case)
            case_copy["__suite__"] = suite
            case_copy["__known_skills__"] = sorted(inventory.included)
            case_id = str(case_copy["id"])
            if case_id in cases:
                raise EvalError(f"duplicate eval case id: {case_id}")
            cases[case_id] = case_copy
    return cases


def stage_runtime_plugin(
    repo_root: Path, output: Path, skill_names: list[str]
) -> tuple[Path, str]:
    source_root = repo_root / "skills"
    staged_root = output / STAGED_PLUGIN_DIR
    staged_root.mkdir()
    shutil.copy2(source_root / "plugins.json", staged_root / "plugins.json")
    for skill_name in skill_names:
        shutil.copytree(
            source_root / skill_name,
            staged_root / skill_name,
            ignore=shutil.ignore_patterns("evals"),
        )
    shutil.copy2(repo_root / TRACE_SCHEMA_PATH, staged_root / STAGED_TRACE_SCHEMA)
    return staged_root, hash_tree(staged_root)


def write_contract(output: Path, case: dict[str, Any]) -> str:
    contract = {key: value for key, value in case.items() if key != "__source__"}
    path = output / CONTRACT_FILE
    write_json(path, contract)
    return sha256_file(path)


def behavior_workspace_policy(
    case: dict[str, Any], workspace: Path
) -> dict[str, Any]:
    allow_runtime_reads = (
        case["mode"] == "mutating"
        and case["fixture"]["kind"] == "isolated-git-worktree"
    )
    return {
        "mode": case["mode"],
        "writeAllow": case["fixture"]["writeAllow"],
        "toolWriteAllow": TOOL_WRITE_ALLOW,
        "toolWriteDeny": TOOL_CONTROL_WRITE_DENY,
        "toolEnvironment": {
            "GRADLE_USER_HOME": str(workspace / ".eval-runtime/gradle-home"),
            "TMPDIR": str(workspace / ".eval-runtime/tmp"),
        },
        "commandPolicy": {
            "cwd": str(workspace),
            "directExecution": True,
            "resolvedExecutableRequired": True,
            "shellWrappers": "deny",
            "unsetEnvironment": [
                "BASH_ENV",
                "CI",
                "ENV",
                "GIT_ATTR_NOSYSTEM",
                "GIT_DIR",
                "GIT_DIFF_OPTS",
                "GIT_EXTERNAL_DIFF",
                "GIT_PAGER",
                "GIT_WORK_TREE",
                "GRADLE_OPTS",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "KOTLIN_OPTS",
                "PAGER",
            ],
            "unsetEnvironmentPrefixes": ["GIT_CONFIG_", "ORG_GRADLE_PROJECT_"],
        },
        "readDeny": EVAL_READ_DENY,
        "externalRead": "trusted-read-only-runtime" if allow_runtime_reads else "deny",
        "externalMutation": "deny",
        "network": "deny",
        "connectors": "deny",
        "activationOnly": False,
    }


def activation_workspace_policy() -> dict[str, Any]:
    return {
        "mode": "activation-only",
        "writeAllow": [],
        "readDeny": ["**"],
        "externalRead": "deny",
        "externalMutation": "deny",
        "network": "deny",
        "connectors": "deny",
        "activationOnly": True,
    }


def build_request(
    repo_root: Path,
    plugin_root: Path,
    run_id: str,
    case: dict[str, Any],
    workspace: Path | None,
    plugin_hash: str,
    contract_hash: str,
    baseline_manifest_hash: str | None,
    trust: AdapterTrust,
    fixture_evidence: dict[str, Any] | None = None,
) -> dict[str, Any]:
    source_repo = resolve_descriptor_path(str(repo_root), "source repository path")
    plugin_source = resolve_descriptor_path(str(plugin_root), "staged plugin path")
    trace_schema = resolve_descriptor_path(
        str(plugin_source / STAGED_TRACE_SCHEMA), "staged trace schema path"
    )
    request: dict[str, Any] = {
        "schemaVersion": RUN_SCHEMA_VERSION,
        "runId": run_id,
        "caseId": case["id"],
        "suite": case["__suite__"],
        "sourceRepo": str(source_repo),
        "adapter": {
            "keyId": trust.key_id,
            "name": trust.adapter_name,
            "version": trust.adapter_version,
        },
        "prompt": case["prompt"],
        "promptSha256": sha256_bytes(case["prompt"].encode("utf-8")),
        "pluginSource": str(plugin_source),
        "integrity": {
            "contractSha256": contract_hash,
            "pluginTreeSha256": plugin_hash,
            "baselineManifestSha256": baseline_manifest_hash,
        },
        "requirements": {
            "freshTask": True,
            "promptExact": True,
            "traceSchemaVersion": TRACE_SCHEMA_VERSION,
            "traceSchema": str(trace_schema),
        },
    }
    if fixture_evidence is None:
        request["workspacePolicy"] = activation_workspace_policy()
    else:
        request["workspace"] = str(workspace)
        request["revision"] = dict(fixture_evidence)
        assert workspace is not None
        request["workspacePolicy"] = behavior_workspace_policy(case, workspace)
    return request


def fixture_source(case: dict[str, Any]) -> Path:
    source = Path(str(case["__source__"]).rsplit(":", 1)[0])
    return source.parent / "fixtures"


def validate_run_marker_shape(marker: dict[str, Any]) -> None:
    required = {
        "schemaVersion",
        "runId",
        "caseId",
        "suite",
        "sourceRepo",
        "adapter",
        "requestSha256",
        "workspace",
        "worktreeRegistered",
        "pluginStaging",
        "pluginTreeSha256",
        "contractSha256",
        "baselineManifestSha256",
        "seal",
    }
    allowed = required | {"fixture", "cleaned"}
    if set(marker) - allowed or not required.issubset(marker):
        raise EvalError("run marker has unknown or missing fields")
    if (
        type(marker.get("schemaVersion")) is not int
        or marker.get("schemaVersion") != RUN_SCHEMA_VERSION
    ):
        raise EvalError("run marker has an unsupported schema")
    if any(
        not isinstance(marker.get(field), str) or not marker[field]
        for field in ("runId", "caseId", "sourceRepo")
    ):
        raise EvalError("run marker identity fields must be non-empty strings")
    adapter = marker.get("adapter")
    if not isinstance(adapter, dict) or set(adapter) != {"keyId", "name", "version"}:
        raise EvalError("run marker adapter identity is invalid")
    if any(
        not isinstance(adapter.get(field), str) or not adapter[field]
        for field in ("keyId", "name", "version")
    ):
        raise EvalError("run marker adapter identity fields must be non-empty strings")
    suite = marker.get("suite")
    if not isinstance(suite, str) or suite not in {"activation", "behavior"}:
        raise EvalError("run marker suite is invalid")
    if type(marker.get("worktreeRegistered")) is not bool:
        raise EvalError("run marker worktreeRegistered must be a boolean")
    if not all(
        is_sha256(marker.get(field))
        for field in ("requestSha256", "pluginTreeSha256", "contractSha256")
    ):
        raise EvalError("run marker integrity hashes are invalid")
    baseline_hash = marker.get("baselineManifestSha256")
    if baseline_hash is not None and not is_sha256(baseline_hash):
        raise EvalError("run marker baseline manifest hash is invalid")
    cleaned = marker.get("cleaned", False)
    if type(cleaned) is not bool:
        raise EvalError("run marker cleaned flag must be a boolean")
    plugin_staging = marker.get("pluginStaging")
    workspace = marker.get("workspace")
    if cleaned:
        if plugin_staging is not None or workspace is not None:
            raise EvalError("cleaned run marker still references runner-owned paths")
    elif not isinstance(plugin_staging, str) or not plugin_staging:
        raise EvalError("run marker pluginStaging must be a non-empty path")
    if suite == "activation":
        if workspace is not None or "fixture" in marker or baseline_hash is not None:
            raise EvalError("activation run marker contains behavior-only state")
        if marker["worktreeRegistered"] is not False:
            raise EvalError("activation run marker cannot register a worktree")
    else:
        if not cleaned and (not isinstance(workspace, str) or not workspace):
            raise EvalError("behavior run marker workspace must be a non-empty path")
        if not isinstance(marker.get("fixture"), dict) or baseline_hash is None:
            raise EvalError("behavior run marker is missing fixture state")
    seal = marker.get("seal")
    if not isinstance(seal, dict) or set(seal) != {
        "algorithm",
        "keyId",
        "signature",
    }:
        raise EvalError("run seal has unknown or missing keys")
    if any(not isinstance(seal.get(field), str) or not seal[field] for field in seal):
        raise EvalError("run seal fields must be non-empty strings")


def validate_revision_descriptor(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvalError("prepared request revision must be an object")
    required = {
        "kind",
        "sourceSha",
        "baseSha",
        "setupSha256",
        "baselineCommit",
        "baselineTreeSha256",
    }
    if set(value) != required:
        raise EvalError("prepared request revision fields are invalid")
    kind = value.get("kind")
    if not isinstance(kind, str) or kind not in {
        "isolated-git-worktree",
        "isolated-git-clone",
        "copied-directory",
    }:
        raise EvalError("prepared request revision kind is invalid")
    source_sha = value.get("sourceSha")
    if not isinstance(source_sha, str) or re.fullmatch(
        r"(?:[0-9a-f]{40}|[0-9a-f]{64})", source_sha
    ) is None:
        raise EvalError("prepared request sourceSha is invalid")
    base_sha = value.get("baseSha")
    if base_sha is not None and (
        not isinstance(base_sha, str)
        or re.fullmatch(r"[0-9a-f]{40}", base_sha) is None
    ):
        raise EvalError("prepared request baseSha is invalid")
    if not isinstance(value.get("baselineCommit"), str) or re.fullmatch(
        r"[0-9a-f]{40}", value["baselineCommit"]
    ) is None:
        raise EvalError("prepared request baselineCommit is invalid")
    if not is_sha256(value.get("baselineTreeSha256")):
        raise EvalError("prepared request baseline tree hash is invalid")
    setup_hash = value.get("setupSha256")
    if setup_hash is not None and not is_sha256(setup_hash):
        raise EvalError("prepared request setup hash is invalid")
    return value


def validate_prepared_request(
    run_dir: Path, marker: dict[str, Any], request: dict[str, Any]
) -> dict[str, Any]:
    common = {
        "schemaVersion",
        "runId",
        "caseId",
        "suite",
        "sourceRepo",
        "adapter",
        "prompt",
        "promptSha256",
        "pluginSource",
        "integrity",
        "requirements",
        "workspacePolicy",
    }
    suite = marker["suite"]
    expected = common if suite == "activation" else common | {"workspace", "revision"}
    if set(request) != expected:
        raise EvalError("prepared request has unknown or missing fields")
    if (
        type(request.get("schemaVersion")) is not int
        or request.get("schemaVersion") != RUN_SCHEMA_VERSION
    ):
        raise EvalError("prepared request has an unsupported schema")
    if any(
        not isinstance(request.get(field), str) or not request[field]
        for field in ("runId", "caseId", "suite", "sourceRepo", "prompt", "pluginSource")
    ):
        raise EvalError("prepared request identity fields must be non-empty strings")
    if request["suite"] not in {"activation", "behavior"}:
        raise EvalError("prepared request suite is invalid")
    try:
        prompt_hash = sha256_bytes(request["prompt"].encode("utf-8"))
    except UnicodeError as error:
        raise EvalError("prepared request prompt is not valid UTF-8") from error
    if not is_sha256(request.get("promptSha256")) or request["promptSha256"] != prompt_hash:
        raise EvalError("prepared request prompt hash is invalid")
    for field in ("runId", "caseId", "suite"):
        if request[field] != marker[field]:
            raise EvalError(f"prepared request {field} does not match the run marker")
    adapter = request.get("adapter")
    if (
        not isinstance(adapter, dict)
        or set(adapter) != {"keyId", "name", "version"}
        or adapter != marker["adapter"]
        or any(
            not isinstance(adapter.get(field), str) or not adapter[field]
            for field in ("keyId", "name", "version")
        )
    ):
        raise EvalError("prepared request adapter identity is invalid")

    resolved_run = resolve_descriptor_path(str(run_dir), "prepared run directory")
    source_repo = resolve_descriptor_path(request["sourceRepo"], "prepared sourceRepo")
    marker_source = resolve_descriptor_path(marker["sourceRepo"], "run marker sourceRepo")
    if source_repo != marker_source:
        raise EvalError("prepared sourceRepo does not match the run marker")
    plugin_root = resolve_descriptor_path(request["pluginSource"], "prepared pluginSource")
    marker_plugin = resolve_descriptor_path(marker["pluginStaging"], "run marker pluginStaging")
    if (
        plugin_root != marker_plugin
        or plugin_root.parent != resolved_run
        or plugin_root.name != STAGED_PLUGIN_DIR
    ):
        raise EvalError("prepared plugin path escaped the runner-owned directory")

    requirements = request.get("requirements")
    if not isinstance(requirements, dict) or set(requirements) != {
        "freshTask",
        "promptExact",
        "traceSchemaVersion",
        "traceSchema",
    }:
        raise EvalError("prepared request requirements are invalid")
    if (
        requirements.get("freshTask") is not True
        or requirements.get("promptExact") is not True
        or type(requirements.get("traceSchemaVersion")) is not int
        or requirements.get("traceSchemaVersion") != TRACE_SCHEMA_VERSION
    ):
        raise EvalError("prepared request requirements are unsupported")
    trace_schema = resolve_descriptor_path(
        requirements.get("traceSchema"), "prepared traceSchema"
    )
    expected_trace_schema = resolve_descriptor_path(
        str(plugin_root / STAGED_TRACE_SCHEMA), "staged trace schema path"
    )
    if trace_schema != expected_trace_schema:
        raise EvalError("prepared trace schema escaped the staged plugin")
    if not isinstance(request.get("workspacePolicy"), dict):
        raise EvalError("prepared request workspacePolicy must be an object")

    integrity = request.get("integrity")
    if not isinstance(integrity, dict) or set(integrity) != {
        "contractSha256",
        "pluginTreeSha256",
        "baselineManifestSha256",
    }:
        raise EvalError("prepared request integrity descriptor is invalid")
    if not is_sha256(integrity.get("contractSha256")) or not is_sha256(
        integrity.get("pluginTreeSha256")
    ):
        raise EvalError("prepared request integrity hashes are invalid")
    manifest_hash = integrity.get("baselineManifestSha256")
    if manifest_hash is not None and not is_sha256(manifest_hash):
        raise EvalError("prepared request baseline manifest hash is invalid")
    for field in ("contractSha256", "pluginTreeSha256", "baselineManifestSha256"):
        if integrity[field] != marker[field]:
            raise EvalError(f"prepared request {field} does not match the run marker")

    if suite == "activation":
        if manifest_hash is not None:
            raise EvalError("activation request cannot seal a baseline manifest")
    else:
        workspace = resolve_descriptor_path(request.get("workspace"), "prepared workspace")
        marker_workspace = resolve_descriptor_path(
            marker.get("workspace"), "run marker workspace"
        )
        if (
            workspace != marker_workspace
            or workspace.parent != resolved_run
            or workspace.name != "workspace"
        ):
            raise EvalError("prepared workspace escaped the runner-owned directory")
        revision = validate_revision_descriptor(request.get("revision"))
        if revision != marker.get("fixture"):
            raise EvalError("prepared revision does not match the run marker")
        expected_registered = revision["kind"] == "isolated-git-worktree"
        if marker["worktreeRegistered"] is not expected_registered:
            raise EvalError("prepared worktree registration does not match the fixture")
        if manifest_hash is None:
            raise EvalError("behavior request must seal a baseline manifest")
    return request


def validate_prepared_artifacts(run_dir: Path, request: dict[str, Any]) -> None:
    integrity = request["integrity"]
    try:
        contract_hash = sha256_regular_file(
            run_dir / CONTRACT_FILE,
            "frozen eval contract",
            max_bytes=MAX_JSON_BYTES,
        )
        plugin_hash = hash_tree(run_dir / STAGED_PLUGIN_DIR)
    except (OSError, ValueError, RuntimeError) as error:
        raise EvalError(f"cannot inspect prepared artifacts: {error}") from error
    if contract_hash != integrity["contractSha256"]:
        raise EvalError("eval contract changed after preparation")
    if plugin_hash != integrity["pluginTreeSha256"]:
        raise EvalError("staged runtime plugin changed after preparation")
    manifest_hash = integrity["baselineManifestSha256"]
    if manifest_hash is not None:
        try:
            current_manifest_hash = sha256_regular_file(
                run_dir / BASELINE_MANIFEST_FILE,
                "baseline manifest",
                max_bytes=MAX_JSON_BYTES,
            )
        except (OSError, ValueError) as error:
            raise EvalError(f"cannot read baseline manifest: {error}") from error
        if current_manifest_hash != manifest_hash:
            raise EvalError("baseline manifest changed after preparation")
        manifest = read_object(
            run_dir / BASELINE_MANIFEST_FILE, "baseline manifest"
        )
        if hash_manifest(manifest) != request["revision"]["baselineTreeSha256"]:
            raise EvalError(
                "prepared revision baselineTreeSha256 does not match the baseline manifest"
            )


def validate_prepared_case_binding(
    request: dict[str, Any], case: dict[str, Any]
) -> None:
    if (
        case.get("id") != request["caseId"]
        or case.get("__suite__") != request["suite"]
        or case.get("prompt") != request["prompt"]
    ):
        raise EvalError("frozen eval contract does not match the prepared request")
    if request["suite"] == "activation":
        expected_policy = activation_workspace_policy()
    else:
        fixture = case.get("fixture")
        if not isinstance(fixture, dict):
            raise EvalError("frozen behavior contract has an invalid fixture")
        revision = request["revision"]
        if revision["kind"] != fixture.get("kind"):
            raise EvalError("prepared revision kind does not match the eval contract")
        expected_policy = behavior_workspace_policy(
            case, resolve_descriptor_path(request["workspace"], "prepared workspace")
        )
    if request["workspacePolicy"] != expected_policy:
        raise EvalError("prepared workspacePolicy does not match the eval contract")


def load_run(
    run_dir: Path,
    *,
    trust: AdapterTrust | None = None,
    verify_request: bool = True,
) -> dict[str, Any]:
    try:
        resolved_run = run_dir.resolve()
    except (OSError, ValueError, RuntimeError) as error:
        raise EvalError(f"invalid run directory: {run_dir!s}") from error
    marker_path = resolved_run / RUN_MARKER
    marker = read_object(marker_path, "run marker")
    validate_run_marker_shape(marker)
    if verify_request:
        if marker.get("cleaned") is True:
            raise EvalError("cleaned eval run cannot be verified")
        validate_run_seal(marker, trust)
        request_path = resolved_run / REQUEST_FILE
        try:
            request_hash = sha256_regular_file(
                request_path, "prepared adapter request", max_bytes=MAX_JSON_BYTES
            )
        except (OSError, ValueError, RuntimeError) as error:
            raise EvalError(f"cannot read prepared adapter request: {error}") from error
        if request_hash != marker["requestSha256"]:
            raise EvalError("adapter request changed after preparation")
        request = read_object(request_path, "prepared adapter request")
        validate_prepared_request(resolved_run, marker, request)
        validate_prepared_artifacts(resolved_run, request)
        case = read_object(resolved_run / CONTRACT_FILE, "frozen eval contract")
        validate_prepared_case_binding(request, case)
        marker["_sealedRequest"] = request
        marker["_sealedCase"] = case
    return marker
