#!/usr/bin/env python3
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import secrets
import subprocess
import sys
from collections import defaultdict
from pathlib import Path


EXPECTED_SOURCE_SHA256 = "e373200f6e8ee47096489fbfe3e06d591e1add627777a08244173aaea96b6e98"
AGGREGATE_ID_INDEX_BUCKETS = 128
RESUME_PROBE_MIN_SPACES = 9
RESUME_PROBE_MAX_SPACES = 32
SOURCE_FIELDS = {
    "aggregateId",
    "aggregateName",
    "contextName",
    "member",
    "requestId",
    "resolvedContextAlias",
    "score",
    "sourceAggregateIdIndexKey",
    "sourceAggregateIdIndexMember",
    "sourceAggregateIdIndexScore",
    "sourceEventKey",
    "sourceRequestIds",
    "sourceRequestIndexKey",
    "tenantId",
    "version",
}


def fail(message: str) -> None:
    print(f"data rehearsal oracle failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_digest(value: object) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def strict_equal(actual: object, expected: object) -> bool:
    if type(actual) is not type(expected):
        return False
    if isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(
            strict_equal(actual[key], value) for key, value in expected.items()
        )
    if isinstance(expected, list):
        return len(actual) == len(expected) and all(
            strict_equal(actual_value, expected_value)
            for actual_value, expected_value in zip(actual, expected, strict=True)
        )
    return actual == expected


def resume_probe_lengths(path: Path) -> list[int]:
    try:
        lines = path.read_bytes().splitlines(keepends=True)
    except OSError as error:
        fail(f"cannot read resume probe from {path}: {error}")
    lengths: list[int] = []
    for line in lines:
        if not line.endswith(b"\n"):
            fail("target JSONL must end every row with a newline")
        without_newline = line[:-1]
        lengths.append(len(without_newline) - len(without_newline.rstrip(b" ")))
    return lengths


def seal_interrupted_resume_probe(target_path: Path) -> None:
    lines = target_path.read_bytes().splitlines(keepends=True)
    if len(lines) != 2:
        fail("interrupted resume probe requires exactly two target rows")
    lengths = resume_probe_lengths(target_path)
    if all(
        RESUME_PROBE_MIN_SPACES <= length <= RESUME_PROBE_MAX_SPACES
        for length in lengths
    ):
        return
    if any(length != 0 for length in lengths):
        fail("interrupted target contains an invalid pre-existing resume probe")
    sealed = []
    for line in lines:
        probe_length = RESUME_PROBE_MIN_SPACES + secrets.randbelow(
            RESUME_PROBE_MAX_SPACES - RESUME_PROBE_MIN_SPACES + 1
        )
        sealed.append(line[:-1] + b" " * probe_length + b"\n")
    target_path.write_bytes(b"".join(sealed))


def verify_completed_resume_probe(target_path: Path, target_count: int) -> None:
    lengths = resume_probe_lengths(target_path)
    if (
        len(lengths) != target_count
        or any(
            not RESUME_PROBE_MIN_SPACES <= length <= RESUME_PROBE_MAX_SPACES
            for length in lengths[:2]
        )
        or any(length != 0 for length in lengths[2:])
    ):
        fail("completed target did not preserve the oracle-sealed interrupted prefix")


def load_json_lines(path: Path) -> list[dict[str, object]]:
    try:
        values = [
            json.loads(line)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not values or any(not isinstance(value, dict) for value in values):
        fail(f"{path} must contain non-empty JSON-object lines")
    return values


def load_json(path: Path) -> dict[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(value, dict):
        fail(f"{path} must contain one JSON object")
    return value


def require_string(record: dict[str, object], field: str) -> str:
    value = record.get(field)
    if not isinstance(value, str) or not value:
        fail(f"source field {field} must be a non-empty string")
    return value


def require_integer(record: dict[str, object], field: str) -> int:
    value = record.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        fail(f"source field {field} must be an integer")
    return value


def utf16_code_units(value: str) -> list[int]:
    try:
        encoded = value.encode("utf-16-be")
    except UnicodeEncodeError as error:
        fail(f"invalid Unicode component: {error}")
    return [
        int.from_bytes(encoded[index : index + 2], "big")
        for index in range(0, len(encoded), 2)
    ]


def java_string_hash(value: str) -> int:
    result = 0
    for code_unit in utf16_code_units(value):
        result = (31 * result + code_unit) & 0xFFFFFFFF
    return result - 0x100000000 if result >= 0x80000000 else result


def bucket_for(aggregate_id: str) -> int:
    return java_string_hash(aggregate_id) % AGGREGATE_ID_INDEX_BUCKETS


def base64url(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def decode_base64url(value: str) -> str:
    if value == "":
        return ""
    if any(
        character not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        for character in value
    ):
        fail("invalid Base64URL key component")
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError, ValueError) as error:
        fail(f"invalid Base64URL key component: {error}")
    if base64url(decoded) != value:
        fail("non-canonical Base64URL key component")
    return decoded


def sortable_id(value: str) -> str:
    if not value:
        fail("aggregateId must not be empty")
    return "".join(f"{code_unit:04x}" for code_unit in utf16_code_units(value))


def decode_sortable_id(value: str) -> str:
    if (
        not value
        or len(value) % 4 != 0
        or any(character not in "0123456789abcdef" for character in value)
    ):
        fail("invalid canonical aggregate-ID index member")
    encoded = b"".join(
        int(value[index : index + 4], 16).to_bytes(2, "big")
        for index in range(0, len(value), 4)
    )
    try:
        decoded = encoded.decode("utf-16-be")
    except UnicodeDecodeError as error:
        fail(f"invalid UTF-16 aggregate-ID index member: {error}")
    if sortable_id(decoded) != value:
        fail("non-canonical aggregate-ID index member")
    return decoded


def stream_identity(record: dict[str, object]) -> tuple[str, str, str, str]:
    return (
        require_string(record, "resolvedContextAlias"),
        require_string(record, "aggregateName"),
        require_string(record, "aggregateId"),
        require_string(record, "tenantId"),
    )


def source_layout(identity: tuple[str, str, str, str]) -> dict[str, object]:
    context_alias, aggregate, aggregate_id, tenant_id = identity
    bucket = bucket_for(aggregate_id)
    hash_tag = f"{{{context_alias}.{aggregate}:es:{bucket}}}"
    event_key = f"{hash_tag}:{aggregate_id}@{tenant_id}"
    return {
        "sourceEventKey": event_key,
        "sourceRequestIndexKey": f"{event_key}:req_idx",
        "sourceAggregateIdIndexKey": f"{hash_tag}:ids",
        "sourceAggregateIdIndexMember": f"{aggregate_id}\0{tenant_id}",
        "sourceAggregateIdIndexScore": 0,
    }


def canonical_layout(identity: tuple[str, str, str, str]) -> dict[str, object]:
    context_alias, aggregate, aggregate_id, tenant_id = identity
    bucket = bucket_for(aggregate_id)
    scope = f"{base64url(context_alias)}.{base64url(aggregate)}"
    hash_tag = f"{{v2:es:{scope}:{bucket}}}"
    event_key = f"{hash_tag}:{base64url(aggregate_id)}.{base64url(tenant_id)}"
    return {
        "canonicalEventKey": event_key,
        "canonicalRequestIndexKey": f"{event_key}:req_idx",
        "canonicalAggregateIdIndexKey": f"{hash_tag}:ids",
        "canonicalAggregateIdIndexMember": f"{sortable_id(aggregate_id)}.{base64url(tenant_id)}",
        "canonicalAggregateIdIndexScore": 0,
    }


def validate_source(source: list[dict[str, object]]) -> None:
    streams: dict[tuple[str, str, str, str], list[dict[str, object]]] = defaultdict(list)
    tenants_by_id: dict[tuple[str, str, str], set[str]] = defaultdict(set)
    for record in source:
        if set(record) != SOURCE_FIELDS:
            fail("source inventory fields do not match the pinned v8.8.1 extraction schema")
        identity = stream_identity(record)
        if require_integer(record, "version") <= 0:
            fail("source event version must be positive")
        if require_integer(record, "score") != require_integer(record, "version"):
            fail("source ZSET score must equal the committed stream version")
        request_ids = record.get("sourceRequestIds")
        if (
            not isinstance(request_ids, list)
            or not request_ids
            or any(not isinstance(request_id, str) or not request_id for request_id in request_ids)
            or len(request_ids) != len(set(request_ids))
        ):
            fail("source request-ID SET must be a non-empty unique string list")
        expected_source_layout = source_layout(identity)
        if any(
            not strict_equal(record[field], value)
            for field, value in expected_source_layout.items()
        ):
            fail("source record does not use the exact Wow v8.8.1 event/request/index layout")
        try:
            member = json.loads(require_string(record, "member"))
        except json.JSONDecodeError as error:
            fail(f"source event ZSET member is not JSON: {error}")
        if (
            not isinstance(member, dict)
            or member.get("contextName") != record["contextName"]
            or member.get("aggregateName") != identity[1]
            or member.get("aggregateId") != identity[2]
            or member.get("tenantId") != identity[3]
            or member.get("requestId") != record["requestId"]
            or not strict_equal(member.get("version"), record["version"])
        ):
            fail("source event member identity, requestId, or version is inconsistent")
        streams[identity].append(record)
        tenants_by_id[identity[:3]].add(identity[3])

    if any(len(tenants) != 1 for tenants in tenants_by_id.values()):
        fail("canonical v2 cannot represent one aggregateId owned by multiple tenants")
    for records in streams.values():
        versions = [require_integer(record, "version") for record in records]
        if versions != list(range(1, len(records) + 1)):
            fail("source event versions must be ordered and contiguous from 1")
        committed_request_ids = [require_string(record, "requestId") for record in records]
        if any(
            set(record["sourceRequestIds"]) != set(committed_request_ids)
            for record in records
        ):
            fail("v8.8.1 per-stream request-ID SET differs from committed event request IDs")


def expected_target(source: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        {
            **canonical_layout(stream_identity(record)),
            "aggregateIdIndexType": "zset",
            "eventType": "zset",
            "member": record["member"],
            "requestId": record["requestId"],
            "requestIndexType": "set",
            "score": record["score"],
        }
        for record in source
    ]


def target_inventory(target: list[dict[str, object]]) -> dict[str, object]:
    event_streams: dict[str, list[dict[str, object]]] = defaultdict(list)
    request_indexes: dict[str, set[str]] = defaultdict(set)
    aggregate_id_indexes: set[tuple[str, str, int]] = set()
    for record in target:
        event_streams[str(record["canonicalEventKey"])].append(
            {"member": record["member"], "score": record["score"]}
        )
        request_indexes[str(record["canonicalRequestIndexKey"])].add(str(record["requestId"]))
        aggregate_id_indexes.add(
            (
                str(record["canonicalAggregateIdIndexKey"]),
                str(record["canonicalAggregateIdIndexMember"]),
                int(record["canonicalAggregateIdIndexScore"]),
            )
        )
    ordered_events = [
        {"key": key, "members": sorted(members, key=lambda member: int(member["score"]))}
        for key, members in sorted(event_streams.items())
    ]
    ordered_requests = [
        {"key": key, "members": sorted(members)}
        for key, members in sorted(request_indexes.items())
    ]
    ordered_ids = [
        {"key": key, "member": member, "score": score}
        for key, member, score in sorted(aggregate_id_indexes)
    ]
    return {
        "aggregateIdIndexes": ordered_ids,
        "eventStreams": ordered_events,
        "requestIndexes": ordered_requests,
    }


def decoded_aggregate_id_scan(
    target: list[dict[str, object]],
) -> list[tuple[str, str, str, str, int]]:
    decoded: set[tuple[str, str, str, str, int]] = set()
    for record in target:
        index_key = str(record["canonicalAggregateIdIndexKey"])
        if not index_key.startswith("{v2:es:") or not index_key.endswith("}:ids"):
            fail("invalid canonical aggregate-ID index key")
        body = index_key[len("{v2:es:") : -len("}:ids")]
        try:
            scope, bucket_token = body.rsplit(":", 1)
            alias_token, aggregate_token = scope.split(".")
            bucket = int(bucket_token)
        except (ValueError, TypeError):
            fail("invalid canonical aggregate-ID index scope or bucket")
        if not 0 <= bucket < AGGREGATE_ID_INDEX_BUCKETS or str(bucket) != bucket_token:
            fail("canonical aggregate-ID index bucket is outside 0..127")
        member = str(record["canonicalAggregateIdIndexMember"])
        try:
            aggregate_id_token, tenant_token = member.split(".")
        except ValueError:
            fail("invalid canonical aggregate-ID index member tuple")
        resolved_alias = decode_base64url(alias_token)
        aggregate_name = decode_base64url(aggregate_token)
        if not resolved_alias or not aggregate_name:
            fail("canonical aggregate-ID index scope components must not be empty")
        aggregate_id = decode_sortable_id(aggregate_id_token)
        tenant_id = decode_base64url(tenant_token)
        identity = (resolved_alias, aggregate_name, aggregate_id, tenant_id)
        expected_layout = canonical_layout(identity)
        if (
            index_key != expected_layout["canonicalAggregateIdIndexKey"]
            or member != expected_layout["canonicalAggregateIdIndexMember"]
            or bucket != bucket_for(aggregate_id)
        ):
            fail("decoded aggregate-ID index does not round-trip through canonical v2")
        decoded.add((*identity, bucket))
    return sorted(decoded)


def expected_checkpoint(
    target_count: int, complete: bool, target_path: Path
) -> dict[str, object]:
    return {
        "complete": complete,
        "lastSourceIndex": target_count - 1,
        "sourceSha256": EXPECTED_SOURCE_SHA256,
        "targetEventCount": target_count,
        "targetSha256": sha256(target_path),
    }


def expected_reconciliation(
    target: list[dict[str, object]],
    target_path: Path,
) -> dict[str, object]:
    inventory = target_inventory(target)
    request_indexes = {
        index["key"]: index["members"] for index in inventory["requestIndexes"]
    }
    stream_summaries = []
    for stream in inventory["eventStreams"]:
        members = stream["members"]
        request_ids = request_indexes[f"{stream['key']}:req_idx"]
        stream_summaries.append(
            {
                "canonicalEventKey": stream["key"],
                "firstVersion": members[0]["score"],
                "lastVersion": members[-1]["score"],
                "memberCount": len(members),
                "orderedMemberScoreSha256": canonical_digest(members),
                "requestIdCount": len(request_ids),
                "requestIdsSha256": canonical_digest(request_ids),
            }
        )
    aggregate_id_scan = decoded_aggregate_id_scan(target)
    return {
        "aggregateIdIndexBucketCount": AGGREGATE_ID_INDEX_BUCKETS,
        "aggregateIdIndexEntryCount": len(inventory["aggregateIdIndexes"]),
        "aggregateIdIndexSha256": canonical_digest(inventory["aggregateIdIndexes"]),
        "aggregateIdScanCount": len(aggregate_id_scan),
        "aggregateIdScanSha256": canonical_digest(aggregate_id_scan),
        "orderedEventMemberScoreSha256": canonical_digest(inventory["eventStreams"]),
        "requestIndexEntryCount": sum(
            len(index["members"]) for index in inventory["requestIndexes"]
        ),
        "requestIndexSha256": canonical_digest(inventory["requestIndexes"]),
        "sourceEventCount": len(target),
        "sourceRequestIdMismatchCount": 0,
        "sourceSha256": EXPECTED_SOURCE_SHA256,
        "sourceStreamCount": len(inventory["eventStreams"]),
        "targetEventCount": len(target),
        "targetRequestIdMismatchCount": 0,
        "targetSha256": sha256(target_path),
        "targetStreamCount": len(inventory["eventStreams"]),
        "streamSummaries": stream_summaries,
    }


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    phase = sys.argv[2] if len(sys.argv) > 2 else "complete"
    if phase not in {"interrupted", "complete"}:
        fail("phase must be interrupted or complete")
    source_path = root / "source/events.jsonl"
    target_path = root / "rehearsal/target.jsonl"
    checkpoint_path = root / "rehearsal/checkpoint.json"
    reconciliation_path = root / "rehearsal/reconciliation.json"
    migration = root / "migrate-data.py"

    if not source_path.is_file() or sha256(source_path) != EXPECTED_SOURCE_SHA256:
        fail("source inventory changed or has an unexpected checksum")
    source = load_json_lines(source_path)
    validate_source(source)
    expected = expected_target(source)
    target_count = 2 if phase == "interrupted" else len(expected)
    target = load_json_lines(target_path)
    if not strict_equal(target, expected[:target_count]):
        fail("target does not preserve exact members/scores or use the canonical v2 key/index codec")
    checkpoint = expected_checkpoint(target_count, phase == "complete", target_path)
    if not strict_equal(load_json(checkpoint_path), checkpoint):
        fail("durable checkpoint does not match the exact interrupted/completed cursor")

    if phase == "interrupted":
        if reconciliation_path.exists():
            fail("an interrupted run must not publish final reconciliation")
        seal_interrupted_resume_probe(target_path)
        checkpoint["targetSha256"] = sha256(target_path)
        checkpoint_path.write_text(
            json.dumps(checkpoint, sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
        print(
            "Synthetic data rehearsal intermediate oracle passed: 2 records durably "
            "checkpointed and sealed for byte-prefix resume proof."
        )
        return

    verify_completed_resume_probe(target_path, len(expected))
    reconciliation = expected_reconciliation(target, target_path)
    if not strict_equal(load_json(reconciliation_path), reconciliation):
        fail("reconciliation does not prove event, request-index, and 128-bucket ID-index equality")
    if not migration.is_file():
        fail("migrate-data.py is missing")

    before = {
        path: sha256(path)
        for path in (target_path, checkpoint_path, reconciliation_path)
    }
    rerun = subprocess.run(
        [
            sys.executable,
            str(migration),
            "--source",
            "source/events.jsonl",
            "--target",
            "rehearsal/target.jsonl",
            "--checkpoint",
            "rehearsal/checkpoint.json",
            "--reconciliation",
            "rehearsal/reconciliation.json",
        ],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
        timeout=30,
    )
    if rerun.returncode != 0:
        fail(f"idempotent rerun failed: {rerun.stderr.strip()}")
    after = {
        path: sha256(path)
        for path in (target_path, checkpoint_path, reconciliation_path)
    }
    if after != before:
        fail("completed rerun changed target, checkpoint, or reconciliation content")
    print(
        "Synthetic data rehearsal oracle passed: canonical v2 event/request/ID indexes, "
        "resume, idempotency, and reconciliation."
    )


if __name__ == "__main__":
    main()
