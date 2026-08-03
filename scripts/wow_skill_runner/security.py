"""Trust loading and domain-separated HMAC seals."""

from __future__ import annotations

import hashlib
import hmac
import os
import re
from pathlib import Path
from typing import Any

from .io import canonical_json, read_object, sha256_bytes
from .model import (
    AdapterTrust,
    EvalError,
    RECOVERY_SEAL_DOMAIN,
    RECOVERY_TOMBSTONE,
    RUN_SEAL_DOMAIN,
    UnsupportedEvidence,
)

__all__ = [
    'run_seal_payload',
    'seal_run_marker',
    'validate_run_seal',
    'load_adapter_trust',
    'evidence_signature_payload',
    'validate_attestation',
    'recovery_tombstone_payload',
    'path_identity_hash',
    'seal_recovery_tombstone',
    'load_recovery_tombstone',
]


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
