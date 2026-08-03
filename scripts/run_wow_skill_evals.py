#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import hmac
import json
import os
import re
import shutil
import stat
import subprocess  # nosec B404 - only fixed git commands are executed.
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import validate_wow_skills as package_validator


RUN_SCHEMA_VERSION = 2
TRACE_SCHEMA_VERSION = 2
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
STAGED_TRACE_SCHEMA = "wow-skill-eval-trace.schema.json"
TRACE_SCHEMA_PATH = package_validator.TRACE_SCHEMA_RELATIVE_PATH.as_posix()
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
    "skills/*/evals/**",
    "scripts/run_wow_skill_evals.py",
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


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare and verify black-box Wow Skill evaluation runs."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Wow repository root (default: inferred from this script).",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    list_parser = subparsers.add_parser("list", help="List available eval case ids.")
    list_parser.add_argument(
        "--suite", choices=("all", "activation", "behavior"), default="all"
    )

    prepare = subparsers.add_parser(
        "prepare", help="Prepare one isolated fixture and adapter request."
    )
    prepare.add_argument("--case", required=True, dest="case_id")
    prepare.add_argument("--subject", help="Exact commit-ish for EVAL_SUBJECT.")
    prepare.add_argument("--base", help="Exact commit-ish for EVAL_BASE.")
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument(
        "--adapter-key",
        type=Path,
        required=True,
        help="Protected adapter trust key used to seal the prepared run.",
    )

    verify = subparsers.add_parser(
        "verify", help="Verify adapter trace plus runner-owned workspace evidence."
    )
    verify.add_argument("--run-dir", type=Path, required=True)
    verify.add_argument("--evidence", type=Path, required=True)
    verify.add_argument(
        "--adapter-key",
        type=Path,
        help="Protected adapter trust key; without it verification is UNSUPPORTED.",
    )

    cleanup = subparsers.add_parser(
        "cleanup", help="Remove only the runner-owned fixture workspace."
    )
    cleanup.add_argument("--run-dir", type=Path, required=True)
    cleanup.add_argument(
        "--adapter-key",
        type=Path,
        required=True,
        help="Protected trust key used for cleanup and recovery idempotency.",
    )
    cleanup.add_argument(
        "--force-recovery",
        action="store_true",
        help="Recover fixed runner-owned paths when the run marker is damaged.",
    )
    cleanup.add_argument(
        "--source-repo",
        type=Path,
        help="Explicit source repository required with --force-recovery.",
    )
    return parser.parse_args(argv)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_regular_file(
    path: Path, label: str, *, max_bytes: int | None = None
) -> str:
    descriptor: int | None = None
    try:
        flags = os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags)
        file_stat = os.fstat(descriptor)
        if not stat.S_ISREG(file_stat.st_mode):
            raise EvalError(f"{label} must be a regular file")
        if file_stat.st_nlink != 1:
            raise EvalError(f"{label} must not be hard-linked")
        if max_bytes is not None and file_stat.st_size > max_bytes:
            raise EvalError(f"{label} exceeds {max_bytes} bytes")
        digest = hashlib.sha256()
        total = 0
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = None
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                total += len(chunk)
                if max_bytes is not None and total > max_bytes:
                    raise EvalError(f"{label} exceeds {max_bytes} bytes")
                digest.update(chunk)
        return digest.hexdigest()
    except EvalError:
        raise
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot hash {label}: {error}") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)


def canonical_json(value: Any) -> bytes:
    try:
        return json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError) as error:
        raise EvalError(f"value cannot be canonicalized as JSON: {error}") from error


def run_seal_payload(marker: dict[str, Any]) -> bytes:
    unsigned = dict(marker)
    unsigned.pop("seal", None)
    return RUN_SEAL_DOMAIN + canonical_json(unsigned)


def seal_run_marker(
    marker: dict[str, Any], trust: AdapterTrust
) -> dict[str, Any]:
    sealed = dict(marker)
    sealed.pop("seal", None)
    sealed["seal"] = {
        "algorithm": "hmac-sha256",
        "keyId": trust.key_id,
        "signature": hmac.new(
            trust.secret, run_seal_payload(sealed), hashlib.sha256
        ).hexdigest(),
    }
    return sealed


def validate_run_seal(marker: dict[str, Any], trust: AdapterTrust | None) -> None:
    if trust is None:
        raise UnsupportedEvidence("no protected adapter trust key was provided")
    seal = marker.get("seal")
    if not isinstance(seal, dict) or set(seal) != {
        "algorithm",
        "keyId",
        "signature",
    }:
        raise EvalError("run seal has unknown or missing keys")
    if seal.get("algorithm") != "hmac-sha256":
        raise EvalError("run seal algorithm is unsupported")
    if seal.get("keyId") != trust.key_id:
        raise EvalError("run seal keyId does not match the trust key")
    if marker.get("adapter") != {
        "keyId": trust.key_id,
        "name": trust.adapter_name,
        "version": trust.adapter_version,
    }:
        raise EvalError("run seal adapter identity does not match the trust key")
    signature = seal.get("signature")
    if not isinstance(signature, str) or not re.fullmatch(r"[0-9a-f]{64}", signature):
        raise EvalError("run seal signature is invalid")
    expected = hmac.new(
        trust.secret, run_seal_payload(marker), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(signature, expected):
        raise EvalError("run seal verification failed")


def tree_manifest(root: Path) -> dict[str, dict[str, Any]]:
    manifest: dict[str, dict[str, Any]] = {}
    try:
        paths = sorted(root.rglob("*"))
    except (OSError, ValueError) as error:
        raise EvalError(f"cannot enumerate fixture tree {root}: {error}") from error
    for path in paths:
        try:
            file_stat = path.lstat()
            relative_path = path.relative_to(root)
        except (OSError, ValueError) as error:
            raise EvalError(f"cannot inspect fixture path {path}: {error}") from error
        if ".git" in relative_path.parts:
            continue
        if stat.S_ISLNK(file_stat.st_mode):
            raise EvalError(f"fixture contains forbidden symlink: {path}")
        relative = relative_path.as_posix()
        mode = stat.S_IMODE(file_stat.st_mode)
        if stat.S_ISDIR(file_stat.st_mode):
            manifest[relative] = {"kind": "directory", "mode": mode}
            continue
        if not stat.S_ISREG(file_stat.st_mode):
            raise EvalError(f"fixture contains unsupported file type: {path}")
        if file_stat.st_nlink > 1:
            raise EvalError(f"fixture contains forbidden hard link: {path}")
        try:
            digest = sha256_file(path)
        except OSError as error:
            raise EvalError(f"cannot hash fixture file {path}: {error}") from error
        manifest[relative] = {"kind": "file", "mode": mode, "sha256": digest}
    return manifest


def hash_manifest(manifest: dict[str, dict[str, Any]]) -> str:
    return sha256_bytes(canonical_json(manifest))


def hash_tree(root: Path) -> str:
    return hash_manifest(tree_manifest(root))


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


def load_cases(repo_root: Path) -> dict[str, dict[str, Any]]:
    validation = package_validator.Validation()
    package_validator.validate_trace_schema(repo_root, validation)
    skills_root = repo_root / "skills"
    included = package_validator.validate_plugin_manifest(skills_root, validation)
    skill_dirs = package_validator.validate_inventory(skills_root, included, validation)
    for skill_dir in skill_dirs:
        package_validator.validate_skill(skill_dir, validation)
    activation_cases: list[dict[str, Any]] = []
    behavior_cases: list[dict[str, Any]] = []
    for skill_dir in skill_dirs:
        activation, behavior = package_validator.load_skill_evals(skill_dir, validation)
        activation_cases.extend(activation)
        behavior_cases.extend(behavior)
    package_validator.validate_activation_cases(
        activation_cases, set(included), validation
    )
    package_validator.validate_behavior_cases(behavior_cases, set(included), validation)
    if validation.errors:
        raise EvalError("package validation failed: " + "; ".join(validation.errors))
    cases: dict[str, dict[str, Any]] = {}
    for suite, suite_cases in (
        ("activation", activation_cases),
        ("behavior", behavior_cases),
    ):
        for case in suite_cases:
            case_copy = dict(case)
            case_copy["__suite__"] = suite
            case_copy["__known_skills__"] = sorted(included)
            case_id = str(case_copy["id"])
            if case_id in cases:
                raise EvalError(f"duplicate eval case id: {case_id}")
            cases[case_id] = case_copy
    return cases


def ensure_output_directory(output: Path, repo_root: Path) -> Path:
    try:
        resolved = output.resolve()
        try:
            resolved.relative_to(repo_root.resolve())
        except ValueError:
            pass
        else:
            raise EvalError("eval output must be outside the source repository")
        if resolved.exists():
            if not resolved.is_dir() or any(resolved.iterdir()):
                raise EvalError(f"eval output must be an empty directory: {resolved}")
        else:
            resolved.mkdir(parents=True)
        return resolved
    except EvalError:
        raise
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"invalid eval output path {output!s}: {error}") from error


def write_json(path: Path, value: Any) -> None:
    temporary_path = path.with_name(
        f".{path.name}.{os.urandom(12).hex()}.tmp"
    )
    descriptor: int | None = None
    try:
        serialized = json.dumps(
            value, ensure_ascii=False, indent=2, sort_keys=True
        ) + "\n"
        payload = serialized.encode("utf-8")
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(temporary_path, flags, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            descriptor = None
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
    except (OSError, TypeError, ValueError, UnicodeError, RecursionError) as error:
        raise EvalError(f"cannot write JSON file {path}: {error}") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary_path.unlink(missing_ok=True)
        except OSError:
            pass


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
    allow_runtime_reads = case["mode"] == "mutating" and case["fixture"]["kind"] == "isolated-git-worktree"
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


def fixture_source(case: dict[str, Any]) -> Path:
    source = Path(str(case["__source__"]).rsplit(":", 1)[0])
    return source.parent / "fixtures"


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


def read_object(
    path: Path, label: str, *, require_private: bool = False
) -> dict[str, Any]:
    descriptor: int | None = None
    try:
        flags = os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags)
        file_stat = os.fstat(descriptor)
        if not stat.S_ISREG(file_stat.st_mode):
            raise EvalError(f"invalid {label}: JSON input must be a regular file")
        if file_stat.st_nlink != 1:
            raise EvalError(f"invalid {label}: JSON input must not be hard-linked")
        if require_private and file_stat.st_mode & 0o077:
            raise EvalError(f"{label} must not be readable by group or others")
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = None
            raw = stream.read(MAX_JSON_BYTES + 1)
        if len(raw) > MAX_JSON_BYTES:
            raise EvalError(
                f"invalid {label}: JSON input exceeds {MAX_JSON_BYTES} bytes"
            )
        value = json.loads(raw.decode("utf-8"))
    except EvalError:
        raise
    except (OSError, UnicodeError, ValueError, RecursionError) as error:
        raise EvalError(f"invalid {label}: {error}") from error
    finally:
        if descriptor is not None:
            os.close(descriptor)
    if not isinstance(value, dict):
        raise EvalError(f"{label} must be a JSON object")
    return value


def is_sha256(value: Any) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def resolve_descriptor_path(value: Any, label: str) -> Path:
    if not isinstance(value, str) or not value:
        raise EvalError(f"{label} must be a non-empty path string")
    try:
        return Path(value).resolve()
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"{label} is invalid: {value!r}") from error


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


def load_adapter_trust(path: Path | None) -> AdapterTrust | None:
    if path is None:
        return None
    try:
        resolved = path.resolve(strict=True)
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot read adapter trust key: {error}") from error
    value = read_object(resolved, "adapter key", require_private=True)
    expected = {
        "schemaVersion",
        "keyId",
        "adapterName",
        "adapterVersion",
        "secretHex",
    }
    if (
        set(value) != expected
        or type(value.get("schemaVersion")) is not int
        or value.get("schemaVersion") != 1
    ):
        raise EvalError("adapter trust key has an unsupported schema")
    text_fields = ("keyId", "adapterName", "adapterVersion", "secretHex")
    if any(not isinstance(value.get(key), str) or not value[key] for key in text_fields):
        raise EvalError("adapter trust key fields must be non-empty strings")
    try:
        secret = bytes.fromhex(value["secretHex"])
    except ValueError as error:
        raise EvalError("adapter trust key secretHex is invalid") from error
    if len(secret) < 32:
        raise EvalError("adapter trust key must contain at least 32 secret bytes")
    return AdapterTrust(
        value["keyId"], value["adapterName"], value["adapterVersion"], secret
    )


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


def evidence_signature_payload(evidence: dict[str, Any]) -> bytes:
    unsigned = dict(evidence)
    unsigned.pop("attestation", None)
    return canonical_json(unsigned)


def validate_attestation(
    evidence: dict[str, Any], trust: AdapterTrust | None
) -> None:
    if trust is None:
        raise UnsupportedEvidence("no protected adapter trust key was provided")
    attestation = evidence.get("attestation")
    if not isinstance(attestation, dict):
        raise UnsupportedEvidence("adapter evidence is not cryptographically attested")
    if set(attestation) != {"algorithm", "keyId", "signature"}:
        raise EvalError("adapter attestation has unknown or missing keys")
    if attestation.get("algorithm") != "hmac-sha256":
        raise EvalError("unsupported adapter attestation algorithm")
    if attestation.get("keyId") != trust.key_id:
        raise EvalError("adapter attestation keyId does not match the trust key")
    signature = attestation.get("signature")
    if not isinstance(signature, str) or not re.fullmatch(r"[0-9a-f]{64}", signature):
        raise EvalError("adapter attestation signature is invalid")
    expected = hmac.new(
        trust.secret, evidence_signature_payload(evidence), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(signature, expected):
        raise EvalError("adapter evidence attestation verification failed")


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


def primary_skills(events: list[dict[str, Any]]) -> list[str]:
    return [
        str(event["skill"])
        for event in events
        if event["type"] == "activation" and event["primary"] is True
    ]


def activated_wow_skills(
    case: dict[str, Any], events: list[dict[str, Any]]
) -> list[str]:
    known = set(case["__known_skills__"])
    return [
        str(event["skill"])
        for event in events
        if event["type"] == "activation" and event["skill"] in known
    ]


def command_text(event: dict[str, Any]) -> str:
    return " ".join(event["argv"])


def event_text(event: dict[str, Any]) -> str:
    if event["type"] in {"read", "write"}:
        return str(event["path"])
    if event["type"] == "command":
        return command_text(event)
    return str(event.get("skill", ""))


def manifest_changed_paths(
    baseline: dict[str, dict[str, Any]], current: dict[str, dict[str, Any]]
) -> list[str]:
    all_paths = set(baseline) | set(current)
    return sorted(path for path in all_paths if baseline.get(path) != current.get(path))


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


def matches_exit(actual: int, expected: Any) -> bool:
    if expected == "nonzero":
        return actual != 0
    return actual == expected


def expected_argv(
    value: list[str], marker: dict[str, Any]
) -> list[str]:
    base_sha = marker.get("fixture", {}).get("baseSha")
    expanded: list[str] = []
    for argument in value:
        if "EVAL_BASE" in argument:
            if not isinstance(base_sha, str):
                raise EvalError("command assertion requires a prepared EVAL_BASE")
            argument = argument.replace("EVAL_BASE", base_sha)
        expanded.append(argument)
    return expanded


def expected_command_executable(argv0: str, marker: dict[str, Any]) -> str:
    try:
        if argv0.startswith("./"):
            workspace = marker.get("workspace")
            if not isinstance(workspace, str):
                raise EvalError("workspace-relative command assertion has no workspace")
            executable = (
                Path(workspace) / argv0.removeprefix("./")
            ).resolve(strict=False)
            return (
                "workspace/"
                + executable.relative_to(Path(workspace).resolve()).as_posix()
            )
        resolved = shutil.which(argv0)
        if resolved is None:
            raise UnsupportedEvidence(
                f"runner cannot resolve asserted executable {argv0!r} on PATH"
            )
        return str(Path(resolved).resolve(strict=False))
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot resolve asserted executable {argv0!r}: {error}") from error


def command_event_matches(
    event: dict[str, Any], argv: list[str], marker: dict[str, Any]
) -> bool:
    return (
        event["type"] == "command"
        and event["argv"] == argv
        and event["cwd"] == "."
        and event["executable"] == expected_command_executable(argv[0], marker)
    )


def find_event(
    events: list[dict[str, Any]],
    start: int,
    descriptor: dict[str, Any],
    marker: dict[str, Any],
) -> int | None:
    event_type = descriptor["event"]
    for index in range(start, len(events)):
        event = events[index]
        if event["type"] != event_type:
            continue
        if event_type == "command":
            argv = expected_argv(descriptor["argv"], marker)
            if not command_event_matches(event, argv, marker):
                continue
            if not matches_exit(event["exitCode"], descriptor["exitCode"]):
                continue
        elif not re.search(descriptor["pattern"], event_text(event)):
            continue
        return index
    return None


def check_trace_order(
    descriptors: list[dict[str, Any]],
    events: list[dict[str, Any]],
    marker: dict[str, Any],
) -> bool:
    next_index = 0
    for descriptor in descriptors:
        found = find_event(events, next_index, descriptor, marker)
        if found is None:
            return False
        next_index = found + 1
    return True


def check_command_exit(
    assertion: dict[str, Any],
    events: list[dict[str, Any]],
    marker: dict[str, Any],
) -> bool:
    argv = expected_argv(assertion["argv"], marker)
    return any(
        command_event_matches(event, argv, marker)
        and matches_exit(event["exitCode"], assertion["exitCode"])
        for event in events
    )


def check_pattern_event(
    assertion_type: str, pattern_text: str, events: list[dict[str, Any]]
) -> bool:
    event_type = "read" if "read" in assertion_type.lower() else "write"
    found = any(
        event["type"] == event_type
        and re.search(pattern_text, event_text(event))
        for event in events
    )
    return not found if ".not" in assertion_type else found


def check_sandbox(evidence: dict[str, Any], assertion_type: str) -> bool:
    sandbox = evidence.get("sandbox")
    if not isinstance(sandbox, dict):
        raise UnsupportedEvidence("adapter did not provide sandbox enforcement evidence")
    required = {
        "externalReadBlocked",
        "externalMutationBlocked",
        "networkBlocked",
        "connectorsBlocked",
        "evalContentBlocked",
        "activationOnly",
        "commandPolicyEnforced",
    }
    if set(sandbox) != required:
        raise UnsupportedEvidence("sandbox evidence is incomplete")
    if any(type(sandbox[key]) is not bool for key in required):
        raise EvalError("sandbox evidence values must be booleans")
    if sandbox["evalContentBlocked"] is not True:
        return False
    if sandbox["commandPolicyEnforced"] is not True:
        return False
    if assertion_type == "sandbox.noExternalRead":
        return sandbox["externalReadBlocked"] is True
    return all(
        sandbox[key] is True
        for key in (
            "externalMutationBlocked",
            "networkBlocked",
            "connectorsBlocked",
        )
    )


@dataclass(frozen=True)
class AssertionContext:
    case: dict[str, Any]
    evidence: dict[str, Any]
    events: list[dict[str, Any]]
    paths: list[str]
    diff: str
    unchanged: bool
    marker: dict[str, Any]


def check_primary_skill(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    return (
        primary_skills(context.events) == [context.case["skill"]]
        and activated_wow_skills(context.case, context.events)
        == [context.case["skill"]]
    )


def check_workspace_unchanged(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    return context.unchanged


def check_diff_non_empty(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    return bool(context.paths)


def check_artifact_changed(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return any(re.search(assertion["pattern"], path) for path in context.paths)


def check_diff_regex(assertion: dict[str, Any], context: AssertionContext) -> bool:
    return re.search(assertion["pattern"], context.diff, re.DOTALL) is not None


def require_cart_oracle_gradle_cache(workspace: Path) -> Path:
    gradle_home = workspace / ".eval-runtime/gradle-home"
    wrapper_distributions = list(
        gradle_home.glob("wrapper/dists/**/gradle-*/bin/gradle")
    )
    dependency_cache = gradle_home / "caches/modules-2/files-2.1"
    if not wrapper_distributions or not dependency_cache.is_dir():
        raise UnsupportedEvidence(
            "Cart oracle requires the workspace-local Gradle wrapper and dependency "
            "cache populated by the signed RED/GREEN commands"
        )
    return gradle_home


def gradle_infrastructure_failure(output: str) -> bool:
    normalized = output.lower()
    indicators = (
        "no cached version",
        "offline mode",
        "could not install gradle distribution",
        "unknownhostexception",
        "connectexception",
        "read timed out",
        "pkix path building failed",
        "no matching toolchains found",
        "toolchain download repositories have not been configured",
        "cannot find a java installation",
        "java_home is not set",
        "no 'java' command could be found",
    )
    return any(indicator in normalized for indicator in indicators)


def prepare_oracle_temp(oracle_runtime: Path) -> Path:
    if oracle_runtime.name != ORACLE_RUNTIME_DIR:
        raise EvalError("oracle runtime must use the runner-owned directory name")
    try:
        resolved_parent = oracle_runtime.parent.resolve()
        if oracle_runtime.is_symlink():
            raise EvalError("oracle runtime must not be a symlink")
        if oracle_runtime.exists() and not oracle_runtime.is_dir():
            raise EvalError("oracle runtime must be a directory")
        oracle_runtime.mkdir(exist_ok=True)
        resolved_runtime = oracle_runtime.resolve()
        if resolved_runtime.parent != resolved_parent:
            raise EvalError("oracle runtime escaped its runner-owned parent")
        temp_dir = oracle_runtime / "tmp"
        if temp_dir.is_symlink():
            raise EvalError("oracle temp directory must not be a symlink")
        if temp_dir.exists() and not temp_dir.is_dir():
            raise EvalError("oracle temp path must be a directory")
        temp_dir.mkdir(exist_ok=True)
        if temp_dir.resolve().parent != resolved_runtime:
            raise EvalError("oracle temp directory escaped the oracle runtime")
        return temp_dir
    except EvalError:
        raise
    except (OSError, ValueError, UnicodeError, RuntimeError) as error:
        raise EvalError(f"cannot prepare runner-owned oracle runtime: {error}") from error


def cart_capacity_oracle(
    workspace: Path, expected_limit: int, oracle_runtime: Path
) -> bool:
    hidden_test = (
        workspace
        / "example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartCapacityEvalOracleTest.kt"
    )
    gradlew = workspace / "gradlew"
    if hidden_test.exists() or not gradlew.is_file():
        return False
    hidden_test_source = f'''package me.ahoo.wow.example.domain.cart

import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CartCapacityEvalOracleTest {{
    private fun fullState(): CartState {{
        assertEquals({expected_limit}, MAX_CART_ITEM_SIZE)
        return CartState("eval-cart").also {{ state ->
            repeat(MAX_CART_ITEM_SIZE) {{ index ->
                state.onCartItemAdded(
                    CartItemAdded(CartItem(productId = "product-$index")),
                )
            }}
        }}
    }}

    @Test
    fun `existing product remains accepted at capacity`() {{
        val result = Cart(fullState()).onCommand(
            AddCartItem(productId = "product-0", quantity = 1),
        )
        val changed = assertInstanceOf(CartQuantityChanged::class.java, result)
        assertEquals(2, changed.changed.quantity)
    }}

    @Test
    fun `next distinct product is rejected at capacity`() {{
        assertThrows(IllegalArgumentException::class.java) {{
            Cart(fullState()).onCommand(
                AddCartItem(productId = "new-product", quantity = 1),
            )
        }}
    }}
}}
'''
    gradle_home = require_cart_oracle_gradle_cache(workspace)
    temp_dir = prepare_oracle_temp(oracle_runtime)
    environment = os.environ.copy()
    for key in (
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
    ):
        environment.pop(key, None)
    for key in list(environment):
        if key.startswith(("GIT_CONFIG_", "ORG_GRADLE_PROJECT_")):
            environment.pop(key)
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    environment["TMPDIR"] = str(temp_dir)
    try:
        hidden_test.write_text(hidden_test_source, encoding="utf-8")
        result = subprocess.run(  # nosec B603 - fixed runner-owned executable and argv.
            [
                str(gradlew),
                "--no-build-cache",
                "--no-configuration-cache",
                "--no-daemon",
                "--offline",
                "--rerun-tasks",
                ":example-domain:test",
                "--tests",
                "me.ahoo.wow.example.domain.cart.CartCapacityEvalOracleTest",
            ],
            cwd=workspace,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
            timeout=600,
        )
        if result.returncode != 0 and gradle_infrastructure_failure(
            f"{result.stdout}\n{result.stderr}"
        ):
            raise UnsupportedEvidence(
                "Cart oracle Gradle cache or Java toolchain is unavailable offline"
            )
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False
    finally:
        hidden_test.unlink(missing_ok=True)


def check_cart_capacity_branches(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    expected_limit = 10 if context.case["id"] == "B02-develop-test-first" else 100
    return cart_capacity_oracle(
        Path(context.marker["workspace"]),
        expected_limit=expected_limit,
        oracle_runtime=Path(context.marker["runDir"]) / ORACLE_RUNTIME_DIR,
    )


def review_changed_files(context: AssertionContext) -> tuple[str, set[str]]:
    workspace = Path(context.marker["workspace"])
    fixture = context.marker["fixture"]
    base = fixture.get("baseSha")
    baseline = fixture["baselineCommit"]
    if not isinstance(base, str):
        raise EvalError("reviewed-file assertion requires a base revision")
    merge_base = require_git(workspace, ["merge-base", base, baseline]).strip()
    changed = set(
        require_git(
            workspace, ["diff", "--name-only", merge_base, baseline, "--"]
        ).splitlines()
    )
    return merge_base, changed


def check_reviewed_changed_file(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    _, changed = review_changed_files(context)
    reads = {
        str(event["path"]).removeprefix("workspace/")
        for event in context.events
        if event["type"] == "read" and str(event["path"]).startswith("workspace/")
    }
    return bool(changed & reads)


def check_reviewed_all_changed_files(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    del assertion
    _, changed = review_changed_files(context)
    base = context.marker["fixture"]["baseSha"]
    full_diff_argv = [
        "git",
        "diff",
        "--no-ext-diff",
        "--no-textconv",
        f"{base}...HEAD",
        "--",
    ]
    full_diff_seen = any(
        command_event_matches(event, full_diff_argv, context.marker)
        and event["exitCode"] == 0
        for event in context.events
    )
    if not full_diff_seen:
        return False
    reads = {
        str(event["path"]).removeprefix("workspace/")
        for event in context.events
        if event["type"] == "read" and str(event["path"]).startswith("workspace/")
    }
    return not changed or changed.issubset(reads) or full_diff_seen


def check_trace_pattern(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_pattern_event(
        assertion["type"], assertion["pattern"], context.events
    )


def check_command_assertion(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_command_exit(assertion, context.events, context.marker)


def check_order_assertion(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_trace_order(assertion["events"], context.events, context.marker)


def check_output_regex(assertion: dict[str, Any], context: AssertionContext) -> bool:
    return re.search(
        assertion["pattern"], context.evidence["output"], re.DOTALL
    ) is not None


def check_output_not_regex(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return re.search(
        assertion["pattern"], context.evidence["output"], re.DOTALL
    ) is None


def check_sandbox_assertion(
    assertion: dict[str, Any], context: AssertionContext
) -> bool:
    return check_sandbox(context.evidence, assertion["type"])


def check_process_exit(assertion: dict[str, Any], context: AssertionContext) -> bool:
    return context.evidence["processExitCode"] == assertion["value"]


ASSERTION_CHECKERS = {
    "activation.primarySkill": check_primary_skill,
    "workspace.unchanged": check_workspace_unchanged,
    "diff.nonEmpty": check_diff_non_empty,
    "artifact.changed": check_artifact_changed,
    "diff.regex": check_diff_regex,
    "oracle.cartCapacityBranches": check_cart_capacity_branches,
    "trace.reviewedAllChangedFiles": check_reviewed_all_changed_files,
    "trace.reviewedChangedFile": check_reviewed_changed_file,
    "trace.read": check_trace_pattern,
    "trace.notRead": check_trace_pattern,
    "trace.write": check_trace_pattern,
    "trace.notWrite": check_trace_pattern,
    "command.exit": check_command_assertion,
    "trace.order": check_order_assertion,
    "output.regex": check_output_regex,
    "output.notRegex": check_output_not_regex,
    "sandbox.noExternalRead": check_sandbox_assertion,
    "sandbox.noExternalMutation": check_sandbox_assertion,
    "process.exitCode": check_process_exit,
}


def assertion_passes(
    assertion: dict[str, Any],
    case: dict[str, Any],
    evidence: dict[str, Any],
    events: list[dict[str, Any]],
    paths: list[str],
    diff: str,
    unchanged: bool,
    marker: dict[str, Any],
) -> bool:
    assertion_type = assertion["type"]
    checker = ASSERTION_CHECKERS.get(assertion_type)
    if checker is None:
        raise EvalError(f"runner does not implement assertion {assertion_type}")
    context = AssertionContext(
        case, evidence, events, paths, diff, unchanged, marker
    )
    return checker(assertion, context)


def verify_write_allow(case: dict[str, Any], paths: list[str]) -> list[str]:
    patterns = case["fixture"]["writeAllow"] + TOOL_WRITE_ALLOW
    return [
        path
        for path in paths
        if any(
            fnmatch.fnmatchcase(path, pattern)
            for pattern in TOOL_CONTROL_WRITE_DENY
        )
        or not any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)
    ]


def verify_trace_write_allow(
    case: dict[str, Any], events: list[dict[str, Any]]
) -> list[str]:
    patterns = case["fixture"]["writeAllow"] + TOOL_WRITE_ALLOW
    forbidden: list[str] = []
    for event in events:
        if event["type"] != "write":
            continue
        path = str(event["path"])
        if not path.startswith("workspace/"):
            forbidden.append(path)
            continue
        relative = path.removeprefix("workspace/")
        if any(
            fnmatch.fnmatchcase(relative, pattern)
            for pattern in TOOL_CONTROL_WRITE_DENY
        ) or not any(fnmatch.fnmatchcase(relative, pattern) for pattern in patterns):
            forbidden.append(relative)
    return forbidden


def verify_activation_case(
    case: dict[str, Any], evidence: dict[str, Any], events: list[dict[str, Any]]
) -> list[str]:
    expected = case["expectedSkills"]
    actual = activated_wow_skills(case, events)
    primary = [
        skill
        for skill in primary_skills(events)
        if skill in set(case["__known_skills__"])
    ]
    failures: list[str] = []
    if actual != expected:
        failures.append(f"Wow skill activation mismatch: expected={expected}, actual={actual}")
    if primary != expected:
        failures.append(f"primary Wow skill mismatch: expected={expected}, actual={primary}")
    if any(event["type"] != "activation" for event in events):
        failures.append("activation-only run continued into tool execution")
    sandbox = evidence["sandbox"]
    if sandbox.get("activationOnly") is not True:
        failures.append("adapter did not enforce activation-only termination")
    if not check_sandbox(evidence, "sandbox.noExternalRead"):
        failures.append("activation run did not block external reads")
    if not check_sandbox(evidence, "sandbox.noExternalMutation"):
        failures.append("activation run did not block external mutations")
    if evidence["processExitCode"] != 0:
        failures.append(
            f"adapter process exited with {evidence['processExitCode']}"
        )
    return failures


def verify_behavior_case(
    case: dict[str, Any],
    marker: dict[str, Any],
    evidence: dict[str, Any],
    events: list[dict[str, Any]],
) -> list[str]:
    workspace = Path(marker["workspace"])
    baseline = marker["fixture"]["baselineCommit"]
    manifest_path = Path(marker["runDir"]) / BASELINE_MANIFEST_FILE
    baseline_files = read_object(manifest_path, "baseline manifest")
    current_files = tree_manifest(workspace)
    all_paths = manifest_changed_paths(baseline_files, current_files)
    paths = [
        path
        for path in all_paths
        if not any(fnmatch.fnmatchcase(path, pattern) for pattern in TOOL_WRITE_ALLOW)
    ]
    new_paths = [
        path
        for path in paths
        if path not in baseline_files and current_files[path].get("kind") == "file"
    ]
    diff = workspace_diff(workspace, baseline, new_paths)
    unchanged = not paths and workspace_head(workspace) == baseline
    failures: list[str] = []
    forbidden = verify_write_allow(case, all_paths)
    if forbidden:
        failures.append(f"changed paths escaped writeAllow: {forbidden}")
    trace_forbidden = verify_trace_write_allow(case, events)
    if trace_forbidden:
        failures.append(f"write trace escaped writeAllow: {trace_forbidden}")
    for index, assertion in enumerate(case["assertions"]):
        if not assertion_passes(
            assertion, case, evidence, events, paths, diff, unchanged, marker
        ):
            failures.append(f"assertion[{index}] failed: {assertion['type']}")
    return failures


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


def recovery_tombstone_payload(value: dict[str, Any]) -> bytes:
    unsigned = dict(value)
    unsigned.pop("seal", None)
    return RECOVERY_SEAL_DOMAIN + canonical_json(unsigned)


def path_identity_hash(path: Path, label: str) -> str:
    try:
        return sha256_bytes(os.fsencode(path))
    except (OSError, TypeError, ValueError, UnicodeError) as error:
        raise EvalError(f"cannot encode {label}: {error}") from error


def seal_recovery_tombstone(
    value: dict[str, Any], trust: AdapterTrust
) -> dict[str, Any]:
    sealed = dict(value)
    sealed.pop("seal", None)
    sealed["seal"] = {
        "algorithm": "hmac-sha256",
        "keyId": trust.key_id,
        "signature": hmac.new(
            trust.secret, recovery_tombstone_payload(sealed), hashlib.sha256
        ).hexdigest(),
    }
    return sealed


def load_recovery_tombstone(
    run_dir: Path, source_repo: Path, trust: AdapterTrust
) -> dict[str, Any] | None:
    path = run_dir / RECOVERY_TOMBSTONE
    try:
        path.lstat()
    except FileNotFoundError:
        return None
    except (OSError, ValueError, UnicodeError) as error:
        raise EvalError(f"cannot inspect recovery tombstone: {error}") from error
    value = read_object(path, "recovery tombstone")
    if set(value) != {
        "schemaVersion",
        "runDirSha256",
        "sourceRepo",
        "status",
        "seal",
    }:
        raise EvalError("recovery tombstone has unknown or missing fields")
    if (
        value.get("schemaVersion") != 1
        or value.get("status") != "CLEAN"
        or value.get("runDirSha256")
        != path_identity_hash(run_dir, "recovery run directory")
        or value.get("sourceRepo") != str(source_repo)
    ):
        raise EvalError("recovery tombstone is invalid")
    seal = value.get("seal")
    if not isinstance(seal, dict) or set(seal) != {
        "algorithm",
        "keyId",
        "signature",
    }:
        raise EvalError("recovery tombstone seal is invalid")
    signature = seal.get("signature")
    if (
        seal.get("algorithm") != "hmac-sha256"
        or seal.get("keyId") != trust.key_id
        or not isinstance(signature, str)
        or re.fullmatch(r"[0-9a-f]{64}", signature) is None
    ):
        raise EvalError("recovery tombstone seal is invalid")
    expected = hmac.new(
        trust.secret, recovery_tombstone_payload(value), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(signature, expected):
        raise EvalError("recovery tombstone seal verification failed")
    return value


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


def list_cases(cases: dict[str, dict[str, Any]], suite: str) -> None:
    for case_id, case in sorted(cases.items()):
        if suite == "all" or case["__suite__"] == suite:
            print(f"{case_id}\t{case['__suite__']}\t{case.get('mode', '-')}")


def print_result(value: dict[str, Any]) -> None:
    rendered = json.dumps(value, ensure_ascii=False, sort_keys=True)
    try:
        print(rendered)
    except UnicodeError:
        print(json.dumps(value, ensure_ascii=True, sort_keys=True))


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repo_root = resolve_descriptor_path(
            str(args.repo_root), "source repository path"
        )
        if args.command == "list":
            list_cases(load_cases(repo_root), args.suite)
            return 0
        if args.command == "prepare":
            trust = load_adapter_trust(args.adapter_key)
            assert trust is not None
            cases = load_cases(repo_root)
            case = cases.get(args.case_id)
            if case is None:
                raise EvalError(f"unknown eval case: {args.case_id}")
            output = ensure_output_directory(args.output, repo_root)
            marker = prepare_case(
                repo_root, output, case, args.subject, args.base, trust
            )
            print_result(
                {
                    "runId": marker["runId"],
                    "caseId": marker["caseId"],
                    "request": str(output / REQUEST_FILE),
                    "requestSha256": marker["requestSha256"],
                    "runDir": str(output),
                    "status": "PREPARED",
                }
            )
            return 0
        if args.command == "verify":
            trust = load_adapter_trust(args.adapter_key)
            result = verify_run(
                repo_root, args.run_dir, args.evidence, trust
            )
            print_result(result)
            return 1 if result["status"] == "FAIL" else 0
        trust = load_adapter_trust(args.adapter_key)
        print_result(
            cleanup_run(
                args.run_dir,
                trust,
                force_recovery=args.force_recovery,
                source_repo=args.source_repo,
            )
        )
        return 0
    except UnsupportedEvidence as error:
        print_result({"status": "UNSUPPORTED", "error": str(error)})
        return 3
    except EvalError as error:
        print_result({"status": "ERROR", "error": str(error)})
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
