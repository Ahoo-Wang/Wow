"""Isolated activation and behavior fixture preparation."""

from __future__ import annotations

import os
import shutil
from pathlib import Path
from typing import Any

from .git import (
    ensure_clean_workspace,
    registered_worktree_paths,
    remove_registered_worktree,
    require_git,
    resolve_commit,
    safe_git,
)
from .io import (
    hash_tree,
    resolve_descriptor_path,
    sha256_bytes,
    sha256_file,
    tree_manifest,
    write_json,
)
from .model import (
    AdapterTrust,
    BASELINE_MANIFEST_FILE,
    CONTRACT_FILE,
    EvalError,
    ORACLE_RUNTIME_DIR,
    RECOVERY_TOMBSTONE,
    REQUEST_FILE,
    RUN_MARKER,
    RUN_SCHEMA_VERSION,
    STAGED_PLUGIN_DIR,
)
from .security import seal_run_marker
from .state import build_request, fixture_source, stage_runtime_plugin, write_contract

__all__ = [
    'random_run_id',
    'prepare_activation',
    'prepare_worktree',
    'prepare_clone',
    'prepare_copy',
    'prepare_behavior',
    'prepare_case',
    'cleanup_failed_prepare',
]


def random_run_id() -> str:
    return sha256_bytes(os.urandom(32))[:20]


def prepare_activation(
    repo_root: Path,
    output: Path,
    case: dict[str, Any],
    plugin_root: Path,
    plugin_hash: str,
    contract_hash: str,
    trust: AdapterTrust,
) -> dict[str, Any]:
    run_id = random_run_id()
    request = build_request(
        repo_root,
        plugin_root,
        run_id,
        case,
        None,
        plugin_hash,
        contract_hash,
        None,
        trust,
    )
    request_path = output / REQUEST_FILE
    write_json(request_path, request)
    marker = {
        "schemaVersion": RUN_SCHEMA_VERSION,
        "runId": run_id,
        "caseId": case["id"],
        "suite": "activation",
        "sourceRepo": request["sourceRepo"],
        "adapter": request["adapter"],
        "requestSha256": sha256_file(request_path),
        "workspace": None,
        "worktreeRegistered": False,
        "pluginStaging": request["pluginSource"],
        "pluginTreeSha256": plugin_hash,
        "contractSha256": contract_hash,
        "baselineManifestSha256": None,
    }
    return marker


def prepare_worktree(
    repo_root: Path,
    workspace: Path,
    fixture: dict[str, Any],
    subject: str | None,
    base: str | None,
    fixtures_root: Path,
) -> dict[str, Any]:
    if not subject:
        raise EvalError("isolated-git-worktree requires --subject")
    subject_sha = resolve_commit(repo_root, subject, "subject")
    base_sha = None
    if fixture.get("baseRevision") == "EVAL_BASE":
        if not base:
            raise EvalError("this review fixture requires --base")
        base_sha = resolve_commit(repo_root, base, "base")
    elif fixture.get("baseRevision") == "EVAL_SUBJECT":
        base_sha = subject_sha
    require_git(repo_root, ["worktree", "add", "--detach", str(workspace), subject_sha])
    evidence: dict[str, Any] = {
        "kind": "isolated-git-worktree",
        "sourceSha": subject_sha,
        "baseSha": base_sha,
        "setupSha256": None,
    }
    setup = fixture["setup"]
    if setup != "none":
        patch_path = (fixtures_root / setup).resolve(strict=True)
        evidence["setupSha256"] = sha256_file(patch_path)
        require_git(workspace, ["apply", "--check", str(patch_path)])
        require_git(workspace, ["apply", str(patch_path)])
        require_git(workspace, ["add", "-A"])
        require_git(
            workspace,
            [
                "-c",
                "user.name=Wow Skill Eval",
                "-c",
                "user.email=wow-skill-eval@invalid.local",
                "commit",
                "-m",
                f"eval fixture {fixture['fixtureId']}",
            ],
        )
    evidence["baselineCommit"] = resolve_commit(workspace, "HEAD", "baseline")
    evidence["baselineTreeSha256"] = hash_tree(workspace)
    ensure_clean_workspace(workspace)
    return evidence


def prepare_clone(
    repo_root: Path,
    workspace: Path,
    fixture: dict[str, Any],
    subject: str | None,
    base: str | None,
    fixtures_root: Path,
) -> dict[str, Any]:
    if not subject:
        raise EvalError("isolated-git-clone requires --subject")
    subject_sha = resolve_commit(repo_root, subject, "subject")
    base_sha = None
    if fixture.get("baseRevision") == "EVAL_BASE":
        if not base:
            raise EvalError("this review fixture requires --base")
        base_sha = resolve_commit(repo_root, base, "base")
    elif fixture.get("baseRevision") == "EVAL_SUBJECT":
        base_sha = subject_sha
    clone = safe_git(
        repo_root,
        [
            "clone",
            "--no-local",
            "--no-checkout",
            str(repo_root),
            str(workspace),
        ],
        timeout=300,
    )
    if clone.returncode != 0:
        raise EvalError(f"failed to prepare standalone clone: {clone.stderr.strip()}")
    revisions = [subject_sha]
    if base_sha is not None and base_sha != subject_sha:
        revisions.append(base_sha)
    pack_prefix = workspace / ".git/objects/pack/pack"
    transfer = safe_git(
        repo_root,
        ["pack-objects", "--revs", "--index-version=2", str(pack_prefix)],
        input_text="".join(f"{revision}\n" for revision in revisions),
        timeout=300,
    )
    if transfer.returncode != 0:
        raise EvalError(
            "failed to transfer exact standalone-clone revisions: "
            f"{(transfer.stderr or transfer.stdout).strip()}"
        )
    require_git(workspace, ["checkout", "--detach", subject_sha], timeout=300)
    require_git(workspace, ["remote", "remove", "origin"])
    if base_sha is not None:
        resolve_commit(workspace, base_sha, "standalone clone base")
    evidence: dict[str, Any] = {
        "kind": "isolated-git-clone",
        "sourceSha": subject_sha,
        "baseSha": base_sha,
        "setupSha256": None,
    }
    setup = fixture["setup"]
    if setup != "none":
        patch_path = (fixtures_root / setup).resolve(strict=True)
        evidence["setupSha256"] = sha256_file(patch_path)
        require_git(workspace, ["apply", "--check", str(patch_path)])
        require_git(workspace, ["apply", str(patch_path)])
        require_git(workspace, ["add", "-A"])
        require_git(
            workspace,
            [
                "-c",
                "user.name=Wow Skill Eval",
                "-c",
                "user.email=wow-skill-eval@invalid.local",
                "commit",
                "-m",
                f"eval fixture {fixture['fixtureId']}",
            ],
        )
    evidence["baselineCommit"] = resolve_commit(workspace, "HEAD", "baseline")
    evidence["baselineTreeSha256"] = hash_tree(workspace)
    ensure_clean_workspace(workspace)
    return evidence


def prepare_copy(
    workspace: Path,
    fixture: dict[str, Any],
    fixtures_root: Path,
) -> dict[str, Any]:
    source = (fixtures_root / fixture["repository"]).resolve(strict=True)
    source_hash = hash_tree(source)
    shutil.copytree(source, workspace)
    if source_hash != hash_tree(workspace):
        raise EvalError("copied fixture content hash changed during copy")
    require_git(workspace, ["init"])
    require_git(workspace, ["add", "-A"])
    require_git(
        workspace,
        [
            "-c",
            "user.name=Wow Skill Eval",
            "-c",
            "user.email=wow-skill-eval@invalid.local",
            "commit",
            "-m",
            f"eval fixture {fixture['fixtureId']}",
        ],
    )
    baseline = resolve_commit(workspace, "HEAD", "baseline")
    ensure_clean_workspace(workspace)
    return {
        "kind": "copied-directory",
        "sourceSha": source_hash,
        "baseSha": None,
        "setupSha256": None,
        "baselineCommit": baseline,
        "baselineTreeSha256": hash_tree(workspace),
    }


def prepare_behavior(
    repo_root: Path,
    output: Path,
    case: dict[str, Any],
    subject: str | None,
    base: str | None,
    plugin_root: Path,
    plugin_hash: str,
    contract_hash: str,
    trust: AdapterTrust,
) -> dict[str, Any]:
    run_id = random_run_id()
    workspace = output / "workspace"
    fixture = case["fixture"]
    fixtures_root = fixture_source(case)
    kind = fixture["kind"]
    registered = kind == "isolated-git-worktree"
    try:
        if kind == "isolated-git-worktree":
            fixture_evidence = prepare_worktree(
                repo_root, workspace, fixture, subject, base, fixtures_root
            )
        elif kind == "isolated-git-clone":
            fixture_evidence = prepare_clone(
                repo_root, workspace, fixture, subject, base, fixtures_root
            )
        else:
            fixture_evidence = prepare_copy(workspace, fixture, fixtures_root)
        baseline_manifest = tree_manifest(workspace)
        baseline_manifest_path = output / BASELINE_MANIFEST_FILE
        write_json(baseline_manifest_path, baseline_manifest)
        baseline_manifest_hash = sha256_file(baseline_manifest_path)
        request = build_request(
            repo_root,
            plugin_root,
            run_id,
            case,
            workspace,
            plugin_hash,
            contract_hash,
            baseline_manifest_hash,
            trust,
            fixture_evidence,
        )
        request_path = output / REQUEST_FILE
        write_json(request_path, request)
        marker = {
            "schemaVersion": RUN_SCHEMA_VERSION,
            "runId": run_id,
            "caseId": case["id"],
            "suite": "behavior",
            "sourceRepo": request["sourceRepo"],
            "adapter": request["adapter"],
            "requestSha256": sha256_file(request_path),
            "workspace": request["workspace"],
            "worktreeRegistered": registered,
            "fixture": fixture_evidence,
            "pluginStaging": request["pluginSource"],
            "pluginTreeSha256": plugin_hash,
            "contractSha256": contract_hash,
            "baselineManifestSha256": baseline_manifest_hash,
        }
        return marker
    except Exception:
        if registered and workspace.exists():
            remove_registered_worktree(repo_root, workspace)
        elif workspace.exists():
            shutil.rmtree(workspace)
        raise


def prepare_case(
    repo_root: Path,
    output: Path,
    case: dict[str, Any],
    subject: str | None,
    base: str | None,
    trust: AdapterTrust,
) -> dict[str, Any]:
    worktree_may_be_registered = (
        case["__suite__"] == "behavior"
        and case["fixture"]["kind"] == "isolated-git-worktree"
    )
    try:
        repo_root = resolve_descriptor_path(str(repo_root), "source repository path")
        output = resolve_descriptor_path(str(output), "eval output path")
        contract_hash = write_contract(output, case)
        plugin_root, plugin_hash = stage_runtime_plugin(
            repo_root, output, case["__known_skills__"]
        )
        if case["__suite__"] == "activation":
            marker = prepare_activation(
                repo_root,
                output,
                case,
                plugin_root,
                plugin_hash,
                contract_hash,
                trust,
            )
        else:
            marker = prepare_behavior(
                repo_root,
                output,
                case,
                subject,
                base,
                plugin_root,
                plugin_hash,
                contract_hash,
                trust,
            )
        sealed_marker = seal_run_marker(marker, trust)
        write_json(output / RUN_MARKER, sealed_marker)
        return sealed_marker
    except Exception as error:
        try:
            cleanup_failed_prepare(
                repo_root,
                output,
                worktree_may_be_registered=worktree_may_be_registered,
            )
        except Exception as cleanup_error:
            raise EvalError(
                f"prepare failed: {error}; failed to restore the empty output "
                f"directory: {cleanup_error}"
            ) from error
        if isinstance(error, EvalError):
            raise
        raise EvalError(f"prepare failed: {error}") from error


def cleanup_failed_prepare(
    repo_root: Path,
    output: Path,
    *,
    worktree_may_be_registered: bool,
) -> None:
    workspace = output / "workspace"
    if worktree_may_be_registered:
        registered_paths = registered_worktree_paths(repo_root)
        if str(workspace) in registered_paths:
            remove_registered_worktree(repo_root, workspace)
        elif workspace.is_symlink():
            workspace.unlink()
        elif workspace.exists():
            shutil.rmtree(workspace)
    elif workspace.is_symlink():
        workspace.unlink()
    elif workspace.exists():
        shutil.rmtree(workspace)

    for directory_name in (STAGED_PLUGIN_DIR, ORACLE_RUNTIME_DIR):
        directory = output / directory_name
        if directory.is_symlink():
            directory.unlink()
        elif directory.exists():
            shutil.rmtree(directory)
    for file_name in (
        RUN_MARKER,
        REQUEST_FILE,
        CONTRACT_FILE,
        BASELINE_MANIFEST_FILE,
        RECOVERY_TOMBSTONE,
    ):
        path = output / file_name
        if path.exists() or path.is_symlink():
            path.unlink()
    remaining = sorted(path.name for path in output.iterdir())
    if remaining:
        raise EvalError(f"unexpected prepare artifacts remain: {remaining}")
