---
name: "wow-migrate"
description: "Handle downstream cross-major or otherwise breaking Wow migrations across pinned releases, Wow application source/configuration/generated/runtime contracts, or Wow-managed storage/data. First adoption without history conversion belongs to wow-develop; history conversion, reconciliation, cutover, or incompatible-write rollback belongs here. Exclude the Wow framework repository, generic migrations, routine same-major non-breaking upgrades, and ordinary development/review/failures."
---

# Migrate Wow Across Breaking Boundaries

## Scope gate

Use only for a downstream application; the Wow framework repository and its modules never qualify. Require scoped source/target to use or introduce Wow plus a cross-major upgrade, known source/configuration/generated/runtime break, or Wow-managed storage/data change. Routine same-major non-breaking upgrades and first adoption stay with `wow-develop` unless adoption requires Wow-managed history/compatibility conversion, data reconciliation/cutover, or rollback after incompatible target writes. Checkout-wide markers and unrelated application contracts do not qualify. Otherwise state that this Skill does not apply and stop using it.

Own the complete migration task. Treat breaking release upgrades—including cross-major and same-major source/config contract changes—and storage/data cutovers as platform, source, generated-contract, data, runtime, and release migrations rather than dependency bumps.

## Contract

- Pin the exact source and target versions before implementation. “v8” alone is not an executable target.
- Use the target tag's source, tests, BOM, release notes, and generated contracts as authority.
- Distinguish audit, planning, code implementation, data rehearsal, production cutover, and review modes.
- Treat code-write authorization, data-write authorization, and production-release authorization as separate scopes.
- Mark missing production, data, or deployment evidence as `MISSING EVIDENCE`.
- Never claim migration completion from dependency resolution, compilation, startup, or a smoke test alone.
- Keep separate evidence gates for compile/test/build, resolved dependencies, `runtimeClasspath`, application startup, real HTTP requests, external integration/data validation, deployability, and production readiness. A passing gate never proves a later gate.

## Access matrix

- A target checkout explicitly placed in scope authorizes read-only inspection of source, tests, generated artifacts, and checked-in non-secret configuration.
- Do not read `.env` files, credentials, tokens, private keys, secret-manager values, or decrypted configuration without explicit authorization and a demonstrated need. Never print full configuration files or secret values; inspect key names, schemas, and redacted metadata, and recommend rotation when plaintext credentials are discovered.
- Production metadata, logs, deployment state, and application data each require an exact environment/target plus explicit read authorization; one does not imply another.
- Local/code writes, non-production data writes, production data writes, traffic changes, and releases are separate authorizations. Never infer a broader scope from a narrower one.
- Record every inaccessible scope as `MISSING EVIDENCE`; do not bypass it with another credential, environment, or copied data source.

## Workflow

Execute only the stages required by the requested mode and explicitly authorized scope. Audit, planning, review, and diagnosis remain read-only unless a code fix is requested. `Adapt` requires code-write authorization. `Rehearse data` and every write performed by `Validate integrations and data` require an exact isolated target plus explicit non-production data-write authorization. Cutover, traffic changes, production data writes, and release each require their own explicit production authorization. Skip data conversion when target-specific evidence proves no conversion or rebuild is needed.

1. **Baseline**: resolve current Wow, Spring Boot, Kotlin/KSP, Java, storage, messaging, runtime customization, generated contracts, and deployment topology.
2. **Target**: pin the exact target release and verify its platform and storage contracts from that tag.
3. **Matrix**: classify every dependency, source API, generated artifact, configuration, runtime owner, store, and release gate.
4. **Adapt**: change platform and source in dependency order; regenerate contracts from their source.
5. **Prove runtime and transport**: inspect the resolved runtime graph and `runtimeClasspath`, perform an isolated startup, exercise the application's actual HTTP or messaging surface, and stop gracefully using `references/runtime-rest-validation.md`. Keep original and temporary-isolation results separate.
6. **Rehearse data**: only with an exact isolated target and data-write authorization, inventory source and target, back up, dry-run, checksum, reconcile, and prove resume/idempotency.
7. **Validate integrations and data**: with separately authorized targets, verify write, idempotency, replay, query, regeneration, external integrations, monitoring, and shutdown; otherwise record `MISSING EVIDENCE`.
8. **Cut over**: stop and drain all source-version writers, migrate offline, reconcile, switch traffic, then scale only the target version.
9. **Observe and roll back**: monitor agreed signals and preserve distinct rollback paths for zero and non-zero target-version production writes.

## Load by risk

| Scope | Load |
|---|---|
| Platform, source, generated metadata, runtime or custom store audit | `references/migration-risk-map.md` |
| Spring Boot 3 to 4 platform, starter/capability, configuration or Jackson migration | `references/spring-boot-3-to-4.md` |
| Runtime dependency, isolated startup, HTTP/REST or actual transport, and shutdown validation | `references/runtime-rest-validation.md` |
| Data inventory, rehearsal, cutover, reconciliation or rollback | `references/cutover-evidence.md` |
| Static discovery of v6 exposure | Read `scripts/audit-v6-usage.sh`, run it with `--help`, then execute it with the explicit target application/module root—not a monorepo default |
| Migration plan or status report | Copy `assets/migration-matrix.md` and fill only evidence-backed fields |

The audit script reports leads, not incompatibility proof or completeness. Verify every match against the pinned target tag.

## Hard safety gates

- Do not write application data without explicit environment and scope authorization.
- Do not call external business APIs without explicit target and authorization. Prefer read-only local probes; mark blocked external or data validation as `MISSING EVIDENCE`.
- Keep isolation-only arguments, profiles, configuration, and dependency experiments out of the committed migration unless independently justified as the permanent fix.
- Verify recoverable backups, exact targets, inventory, stop conditions, dry-run, resume, checksum, and failure-closed behavior before migration writes.
- Do not run old and new writers together unless the pinned target contract explicitly provides and verifies a mixed-version protocol.
- Do not use broad deletion, ownership-marker edits, or configuration suppression to hide conflicts.
- Do not claim production readiness without rollback rehearsal and reconciliation evidence.

## Completion

Report the baseline and pinned target, changed files/contracts, original startup result, every temporary isolation argument or dependency, runtime classpath gaps, applicable HTTP or transport evidence, REST binding regressions, external/data/cutover/rollback state, and every `MISSING EVIDENCE` item. State separately whether migration code is complete, local runtime passed, the artifact is deployable, and production readiness is proved; none implies the next.
