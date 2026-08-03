"""Bounded filesystem, JSON, and hashing primitives."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from pathlib import Path
from typing import Any

from .model import EvalError, MAX_JSON_BYTES

__all__ = [
    'sha256_bytes',
    'sha256_file',
    'sha256_regular_file',
    'canonical_json',
    'tree_manifest',
    'hash_manifest',
    'hash_tree',
    'ensure_output_directory',
    'write_json',
    'read_object',
    'is_sha256',
    'resolve_descriptor_path',
]


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
