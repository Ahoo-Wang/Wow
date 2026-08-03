---
name: "wow-migrate"
description: "Audit, plan, implement, debug, review, and verify breaking application migrations to a pinned Wow release, including cross-major compatibility, known same-major source or configuration breaks, and storage-format or data cutovers even when both versions are v8. Use only when service migration, data reconciliation, cutover, rollout, or rollback is the primary outcome. Do not use for first-time adoption, routine same-major service upgrades with no known breaking contract and no data migration, framework release preparation or version metadata, repository tooling, or ordinary development, review, and failures."
---

# Migrate Wow Across Breaking Boundaries

Own the complete migration task. Treat breaking release upgrades—including cross-major and same-major source/config contract changes—and storage/data cutovers as platform, source, generated-contract, data, runtime, and release migrations rather than dependency bumps.

## Contract

- Pin the exact source and target versions before implementation. “v8” alone is not an executable target.
- Use the target tag's source, tests, BOM, release notes, and generated contracts as authority.
- Distinguish audit, planning, code implementation, data rehearsal, production cutover, and review modes.
- Treat code-write authorization, data-write authorization, and production-release authorization as separate scopes.
- Mark missing production, data, or deployment evidence as `MISSING EVIDENCE`.
- Never claim migration completion from dependency resolution, compilation, startup, or a smoke test alone.

## Access matrix

- A target checkout explicitly placed in scope authorizes read-only inspection of source, tests, generated artifacts, and checked-in non-secret configuration.
- Do not read `.env` files, credentials, tokens, private keys, secret-manager values, or decrypted configuration without explicit authorization and a demonstrated need. Prefer names, schemas, and redacted metadata.
- Production metadata, logs, deployment state, and application data each require an exact environment/target plus explicit read authorization; one does not imply another.
- Local/code writes, non-production data writes, production data writes, traffic changes, and releases are separate authorizations. Never infer a broader scope from a narrower one.
- Record every inaccessible scope as `MISSING EVIDENCE`; do not bypass it with another credential, environment, or copied data source.

## Workflow

Execute only the stages required by the requested mode and explicitly authorized scope. Audit, planning, review, and diagnosis remain read-only unless a code fix is requested. `Adapt` requires code-write authorization. Data rehearsal and every write performed by `Validate` require an exact isolated target plus explicit non-production data-write authorization. Cutover, traffic changes, production data writes, and release each require their own explicit production authorization. Skip data conversion when target-specific evidence proves no conversion or rebuild is needed.

1. **Baseline**: resolve current Wow, Spring Boot, Kotlin/KSP, Java, storage, messaging, runtime customization, generated contracts, and deployment topology.
2. **Target**: pin the exact target release and verify its platform and storage contracts from that tag.
3. **Matrix**: classify every dependency, source API, generated artifact, configuration, runtime owner, store, and release gate.
4. **Adapt**: change platform and source in dependency order; regenerate contracts from their source.
5. **Rehearse data**: inventory source and target, back up, dry-run, checksum, reconcile, and prove resume/idempotency in isolation.
6. **Validate**: only with the isolated-target and data-write authorization above, start one target-version instance and verify write, idempotency, replay, query, regeneration, monitoring, and graceful shutdown.
7. **Cut over**: stop and drain all source-version writers, migrate offline, reconcile, switch traffic, then scale only the target version.
8. **Observe and roll back**: monitor agreed signals and preserve distinct rollback paths for zero and non-zero target-version production writes.

## Load by risk

| Scope | Load |
|---|---|
| Platform, source, generated metadata, runtime or custom store audit | `references/migration-risk-map.md` |
| Data inventory, rehearsal, cutover, reconciliation or rollback | `references/cutover-evidence.md` |
| Static discovery of v6 exposure | Read `scripts/audit-v6-usage.sh`, run it with `--help`, then execute it against the target checkout |
| Migration plan or status report | Copy `assets/migration-matrix.md` and fill only evidence-backed fields |

The audit script reports leads, not incompatibility proof or completeness. Verify every match against the pinned target tag.

## Hard safety gates

- Do not write application data without explicit environment and scope authorization.
- Verify recoverable backups, exact targets, inventory, stop conditions, dry-run, resume, checksum, and failure-closed behavior before migration writes.
- Do not run old and new writers together unless the pinned target contract explicitly provides and verifies a mixed-version protocol.
- Do not use broad deletion, ownership-marker edits, or configuration suppression to hide conflicts.
- Do not claim production readiness without rollback rehearsal and reconciliation evidence.

## Completion

Report the baseline and pinned target, migration matrix status, changed files and contracts, executed verification, data/cutover/rollback state, and every `MISSING EVIDENCE` item. Separate completed code work from unexecuted environment operations.
