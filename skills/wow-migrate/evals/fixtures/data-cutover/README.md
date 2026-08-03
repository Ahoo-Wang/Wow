# Synthetic Redis EventStore canonical-v2 rehearsal

This fixture is an isolated, non-production rehearsal of the **Redis
EventStore event-data** change from Wow `v8.8.1` to `v8.9.6`. It does not model
Redis SnapshotStore or PrepareKey; both remain `MISSING EVIDENCE` for a complete
Redis cutover. `source/events.jsonl` is an immutable synthetic extraction with a
pinned SHA-256 checksum. It contains no credentials or production data.

Each source row records one v8.8.1 event ZSET member and score, the per-stream
request-ID SET, and the 128-bucket aggregate-ID ZSET entry. Treat the raw event
`member` JSON as the authority for `contextName`, `aggregateName`, `aggregateId`,
`tenantId`, `requestId`, and `version`; the duplicated inventory fields must
match it. The source includes a two-version stream and the delimiter/Unicode
identity `order@{42}:雪` / `tenant@east}`.

## Required program

Writes are authorized only to `migrate-data.py` and `rehearsal/` in this copied
fixture. Implement this exact interface:

```text
python3 migrate-data.py \
  --source source/events.jsonl \
  --target rehearsal/target.jsonl \
  --checkpoint rehearsal/checkpoint.json \
  --reconciliation rehearsal/reconciliation.json \
  [--fail-after N]
```

Map source rows in order. Each target JSONL row must contain exactly:

- `eventType="zset"`, the exact canonical-v2 `canonicalEventKey`, and the
  byte-preserved source `member` and numeric `score`;
- `requestIndexType="set"`, the exact `canonicalRequestIndexKey`, and the
  committed event `requestId`;
- `aggregateIdIndexType="zset"`, the exact
  `canonicalAggregateIdIndexKey`, encoded `canonicalAggregateIdIndexMember`,
  and score `0`.

Use the runtime formulas, not delimiter concatenation:

```text
b64(x)       = unpadded Base64URL(UTF-8(x))
scope        = b64(resolvedContextAlias) + "." + b64(aggregateName)
bucket       = Java/Kotlin UTF-16 String.hashCode(aggregateId).mod(128)
hashTag      = "{v2:es:" + scope + ":" + bucket + "}"
identity     = b64(aggregateId) + "." + b64(tenantId)
event key    = hashTag + ":" + identity
request key  = event key + ":req_idx"
ID index key = hashTag + ":ids"
ID member    = lower-case four-hex-digits per UTF-16 code unit of aggregateId
               + "." + b64(tenantId)
```

For the special identity, the target event key is exactly:

```text
{v2:es:b3JkZXItc2VydmljZQ.b3JkZXI:6}:b3JkZXJAezQyfTrpm6o.dGVuYW50QGVhc3R9
```

The request SET for every stream must equal both the committed event request IDs
and its v8.8.1 source SET. Rebuild one score-0 aggregate-ID index member per
stream in its computed bucket. Reject non-contiguous versions, request-ID
differences, cross-tenant ownership of one `(scope, aggregateId)`, or any source
checksum change.

## Interruption and completion gates

`--fail-after N` must durably write exactly the first `N` mapped rows and the
checkpoint, publish no final reconciliation, then exit non-zero. For `N=2`, the
checkpoint must be exactly:

```json
{
  "complete": false,
  "lastSourceIndex": 1,
  "sourceSha256": "<pinned source checksum>",
  "targetEventCount": 2,
  "targetSha256": "<partial target checksum>"
}
```

A later invocation without `--fail-after` must verify the source/checkpoint and
resume at index 2 without truncating or rewriting the first two target rows. The
`interrupted` oracle seals those rows with unpredictable, JSON-insignificant
trailing whitespace and updates the checkpoint's `targetSha256`; the completed
target must preserve that exact byte prefix while appending rows 3 and 4. This
makes a restart-from-zero implementation observably fail even if it recreates the
same parsed JSON. The completed checkpoint uses `complete=true`, cursor 3, and
count 4. Final reconciliation must exactly report the source/target checksums,
event/stream/request/index counts, ordered per-stream member+score checksums,
first/last versions, request-ID equality, the 128-bucket index checksum, and the
decoded aggregate-ID scan checksum. Re-running a completed migration must leave
target, checkpoint, and reconciliation bytes unchanged.

Run the gates in this order:

```text
./verify-data-rehearsal.sh . complete       # baseline: non-zero
python3 migrate-data.py ... --fail-after 2  # expected non-zero
./verify-data-rehearsal.sh . interrupted    # must pass before resume
python3 migrate-data.py ...                 # resume and complete
python3 migrate-data.py ...                 # explicit idempotent rerun
./verify-data-rehearsal.sh . complete       # final oracle
```

The final oracle performs one additional idempotent rerun. Passing proves only
this synthetic EventStore mapping and recovery protocol. Production inventory,
authorization, backup, traffic/drain, real Redis execution, SnapshotStore,
PrepareKey, state replay, deployment, observation, and both rollback paths
remain `MISSING EVIDENCE`.
