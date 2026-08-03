"""Ownership-checked cleanup and recovery orchestration."""

from __future__ import annotations

import shutil
import stat
from pathlib import Path
from typing import Any

from .git import registered_worktree_paths, remove_registered_worktree
from .io import resolve_descriptor_path, write_json
from .model import (
    AdapterTrust,
    EvalError,
    ORACLE_RUNTIME_DIR,
    RECOVERY_TOMBSTONE,
    RUN_MARKER,
    STAGED_PLUGIN_DIR,
    UnsupportedEvidence,
)
from .security import (
    load_recovery_tombstone,
    path_identity_hash,
    seal_recovery_tombstone,
    seal_run_marker,
    validate_run_seal,
)
from .state import load_run

__all__ = [
    'remove_owned_directory',
    'remove_owned_runtime',
    'validate_cleanup_owned_paths',
    'cleanup_owned_paths',
    'cleanup_run',
]


def remove_owned_directory(path: Path, label: str) -> None:
    try:
        try:
            file_stat = path.lstat()
        except FileNotFoundError:
            return
        if stat.S_ISLNK(file_stat.st_mode):
            path.unlink()
            return
        if not stat.S_ISDIR(file_stat.st_mode):
            raise EvalError(f"runner-owned {label} is not a real directory")
        shutil.rmtree(path)
    except EvalError:
        raise
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot remove runner-owned {label}: {error}") from error


def remove_owned_runtime(path: Path) -> None:
    try:
        if path.is_symlink() or (path.exists() and not path.is_dir()):
            path.unlink()
        elif path.exists():
            shutil.rmtree(path)
    except (OSError, ValueError, UnicodeError) as error:
        raise EvalError(f"cannot remove runner-owned oracle runtime: {error}") from error


def validate_cleanup_owned_paths(
    run_dir: Path, marker: dict[str, Any]
) -> None:
    plugin = resolve_descriptor_path(marker["pluginStaging"], "run marker pluginStaging")
    if plugin != run_dir / STAGED_PLUGIN_DIR:
        raise EvalError("run marker pluginStaging escaped the runner-owned directory")
    if marker["suite"] == "behavior":
        workspace = resolve_descriptor_path(marker["workspace"], "run marker workspace")
        if workspace != run_dir / "workspace":
            raise EvalError("run marker workspace escaped the runner-owned directory")


def cleanup_owned_paths(
    run_dir: Path,
    source_repo: Path,
    *,
    include_workspace: bool,
    worktree_may_be_registered: bool,
) -> None:
    if include_workspace:
        workspace = run_dir / "workspace"
        if worktree_may_be_registered:
            registered_paths = registered_worktree_paths(source_repo)
            if str(workspace) in registered_paths:
                remove_registered_worktree(source_repo, workspace)
            else:
                remove_owned_directory(workspace, "workspace")
        else:
            remove_owned_directory(workspace, "workspace")
    remove_owned_directory(run_dir / STAGED_PLUGIN_DIR, "plugin")
    remove_owned_runtime(run_dir / ORACLE_RUNTIME_DIR)


def cleanup_run(
    run_dir: Path,
    trust: AdapterTrust | None,
    *,
    force_recovery: bool = False,
    source_repo: Path | None = None,
) -> dict[str, Any]:
    try:
        resolved = run_dir.resolve()
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"invalid run directory: {run_dir!s}") from error
    recovery_repo: Path | None = None
    if force_recovery:
        if source_repo is None:
            raise EvalError("--force-recovery requires an explicit --source-repo")
        if trust is None:
            raise UnsupportedEvidence("force recovery requires a protected trust key")
        recovery_repo = resolve_descriptor_path(
            str(source_repo), "recovery source repository"
        )
        tombstone = load_recovery_tombstone(resolved, recovery_repo, trust)
        if tombstone is not None:
            return {
                "runId": None,
                "status": "CLEAN",
                "recovery": True,
                "alreadyClean": True,
            }
    marker: dict[str, Any] | None = None
    try:
        marker = load_run(resolved, verify_request=False)
        validate_run_seal(marker, trust)
        if marker.get("cleaned") is True:
            return {"runId": marker["runId"], "status": "CLEAN"}
        validate_cleanup_owned_paths(resolved, marker)
        trusted_repo = resolve_descriptor_path(
            marker["sourceRepo"], "run marker sourceRepo"
        )
    except (EvalError, UnsupportedEvidence):
        if not force_recovery:
            raise
        assert recovery_repo is not None
        trusted_repo = recovery_repo
        cleanup_owned_paths(
            resolved,
            trusted_repo,
            include_workspace=True,
            worktree_may_be_registered=True,
        )
        assert trust is not None
        write_json(
            resolved / RECOVERY_TOMBSTONE,
            seal_recovery_tombstone(
                {
                    "schemaVersion": 1,
                    "runDirSha256": path_identity_hash(
                        resolved, "recovery run directory"
                    ),
                    "sourceRepo": str(trusted_repo),
                    "status": "CLEAN",
                },
                trust,
            ),
        )
        return {
            "runId": marker.get("runId") if marker is not None else None,
            "status": "CLEAN",
            "recovery": True,
        }
    cleanup_owned_paths(
        resolved,
        trusted_repo,
        include_workspace=marker["suite"] == "behavior",
        worktree_may_be_registered=(
            marker["suite"] == "behavior"
            and marker["fixture"]["kind"] == "isolated-git-worktree"
        ),
    )
    marker["workspace"] = None
    marker["pluginStaging"] = None
    marker["cleaned"] = True
    assert trust is not None
    write_json(resolved / RUN_MARKER, seal_run_marker(marker, trust))
    return {"runId": marker["runId"], "status": "CLEAN"}
