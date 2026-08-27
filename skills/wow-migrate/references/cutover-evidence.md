# Cutover Evidence

Use this reference for data migration, isolated validation, traffic switching, reconciliation, and rollback.

## Inventory before writes

For every store or stream record:

- environment, owner, database/namespace/topic and bounded context;
- source and target format/version;
- record/key/stream cardinality and size;
- active writers/readers and how they will be drained;
- backup location and tested restore procedure;
- migration/rebuild action, dry-run, resume cursor, checksum, and stop condition;
- reconciliation query and responsible owner.

Do not begin writes with unknown ownership, incomplete writer inventory, or an untested backup.

## Rehearsal

Use an isolated copy or namespace. Prove:

1. deterministic source→target mapping;
2. safe resume after interruption;
3. idempotent rerun or explicit duplicate rejection;
4. source/target checksums and semantic reconciliation;
5. handling of malformed, orphaned, duplicate, or conflicting records;
6. bounded resource use and observable progress;
7. failure closes without partial traffic exposure.

## Cutover

1. stop ingress and drain every old writer;
2. verify in-flight work and backlog meet the agreed gate;
3. create and verify the final consistent backup;
4. execute migration/rebuild and full reconciliation;
5. start one isolated target instance and verify critical write/read/replay/query paths;
6. switch traffic, then scale only the target version;
7. observe agreed errors, latency, backlog, duplicates, continuity, and reconciliation signals.

## Rollback

Maintain separate procedures for:

- **No target production writes**: stop target traffic and return to untouched source data.
- **Target writes exist**: stop traffic, inventory the delta, reverse-migrate or replay it under an approved contract, reconcile, then restore service.

Do not describe “redeploy the old version” as a rollback when the target has already written incompatible data.
