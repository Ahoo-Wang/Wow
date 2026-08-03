"""Constrained Git and worktree operations."""

from __future__ import annotations

import os
import re
import subprocess  # nosec B404 - only fixed git commands are executed.
from collections.abc import Sequence
from pathlib import Path

from .model import EvalError, GIT_EXECUTABLE

__all__ = [
    'safe_git',
    'require_git',
    'resolve_commit',
    'ensure_clean_workspace',
    'remove_registered_worktree',
    'registered_worktree_paths',
    'workspace_diff',
    'workspace_head',
]


def safe_git(
    repository: Path,
    arguments: Sequence[str],
    *,
    timeout: int = 120,
    input_text: str | None = None,
) -> subprocess.CompletedProcess[str]:
    if GIT_EXECUTABLE is None:
        raise EvalError("git executable is not available on PATH")
    environment = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "LC_ALL": "C",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_TERMINAL_PROMPT": "0",
    }
    try:
        return subprocess.run(  # nosec B603
            [
                GIT_EXECUTABLE,
                "-c",
                f"core.hooksPath={os.devnull}",
                "-C",
                str(repository),
                *arguments,
            ],
            capture_output=True,
            text=True,
            check=False,
            input=input_text,
            stdin=subprocess.DEVNULL if input_text is None else None,
            timeout=timeout,
            env=environment,
        )
    except subprocess.TimeoutExpired as error:
        raise EvalError(f"git {' '.join(arguments)} timed out") from error
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"git {' '.join(arguments)} could not start: {error}") from error


def require_git(
    repository: Path, arguments: Sequence[str], *, timeout: int = 120
) -> str:
    result = safe_git(repository, arguments, timeout=timeout)
    if result.returncode != 0:
        details = (result.stderr or result.stdout).strip()
        raise EvalError(f"git {' '.join(arguments)} failed: {details}")
    return result.stdout.strip()


def resolve_commit(repository: Path, revision: str, label: str) -> str:
    resolved = require_git(repository, ["rev-parse", "--verify", f"{revision}^{{commit}}"])
    if not re.fullmatch(r"[0-9a-f]{40}", resolved):
        raise EvalError(f"{label} did not resolve to an exact commit: {resolved!r}")
    return resolved


def ensure_clean_workspace(workspace: Path) -> None:
    status = require_git(
        workspace, ["status", "--porcelain=v1", "--untracked-files=all"]
    )
    if status:
        raise EvalError(f"prepared fixture is not clean:\n{status}")


def remove_registered_worktree(repo_root: Path, workspace: Path) -> None:
    result = safe_git(repo_root, ["worktree", "remove", "--force", str(workspace)])
    if result.returncode == 0:
        return
    registered_paths = registered_worktree_paths(repo_root)
    if str(workspace) in registered_paths:
        raise EvalError(f"failed to clean worktree: {result.stderr.strip()}")
    try:
        if workspace.is_symlink():
            workspace.unlink()
            return
        if workspace.exists():
            raise EvalError(f"failed to clean worktree: {result.stderr.strip()}")
    except EvalError:
        raise
    except (OSError, ValueError, UnicodeError) as error:
        raise EvalError(f"failed to inspect worktree cleanup: {error}") from error


def registered_worktree_paths(repo_root: Path) -> set[str]:
    listing = require_git(repo_root, ["worktree", "list", "--porcelain", "-z"])
    return {
        field.removeprefix("worktree ")
        for field in listing.split("\0")
        if field.startswith("worktree ")
    }


def workspace_diff(workspace: Path, baseline: str, new_paths: Sequence[str]) -> str:
    tracked = require_git(
        workspace,
        [
            "diff",
            "--binary",
            "--no-ext-diff",
            "--no-textconv",
            baseline,
            "--",
        ],
        timeout=300,
    )
    patches = [tracked] if tracked else []
    for path in sorted(new_paths):
        indexed = safe_git(
            workspace, ["ls-files", "--error-unmatch", "--", path]
        )
        if indexed.returncode == 0:
            continue
        if indexed.returncode != 1:
            details = (indexed.stderr or indexed.stdout).strip()
            raise EvalError(f"cannot inspect new workspace path {path!r}: {details}")
        untracked = safe_git(
            workspace,
            [
                "diff",
                "--no-index",
                "--binary",
                "--no-ext-diff",
                "--no-textconv",
                "--",
                os.devnull,
                path,
            ],
            timeout=300,
        )
        if untracked.returncode not in {0, 1}:
            details = (untracked.stderr or untracked.stdout).strip()
            raise EvalError(f"cannot diff new workspace path {path!r}: {details}")
        if untracked.stdout:
            patches.append(untracked.stdout.rstrip("\n"))
    return "\n".join(patches)


def workspace_head(workspace: Path) -> str:
    return resolve_commit(workspace, "HEAD", "workspace HEAD")
