# Verification Evidence

Use this reference to select evidence proportional to the behavior and risk.

## Evidence ladder

| Change | Minimum focused evidence | Broaden when |
|---|---|---|
| Aggregate behavior | Aggregate spec/verifier | metadata, serialization, routing, storage, or transport changes |
| Saga command choice | Saga spec/verifier including no-command branches | retry, compensation, duplicate delivery, or registration changes |
| Projection/EventProcessor | Unit test around mapping/side effects | delivery, idempotency, retry, scheduling, or infrastructure is part of the claim |
| Query DSL | Builder/converter tests | backend semantics, index usage, or performance is claimed |
| Starter/configuration | Property-binding or auto-config test | real backend selection or startup behavior changes |
| Gateway/wait | Plan/gateway lifecycle tests | transport, propagation, bus, or processor boundary changes |
| Runtime lifecycle | Deterministic concurrency/lifecycle tests | Spring/container integration or real resource drain is claimed |
| Public message/API | Schema/OpenAPI/generated diff plus consumer test | serialized compatibility or deployed consumers are affected |

## Procedure

1. Resolve the real module path from `settings.gradle.kts`.
2. Run the narrowest test that proves the changed contract.
3. Inspect the failure rather than broadening blindly.
4. Run the owning module's `check` when focused evidence passes.
5. Broaden to integration or full build only when the affected boundary justifies it.

Report the exact command, exit result, and what the command proves. Distinguish environment failures from product failures. Do not infer production safety, concurrency correctness, compatibility, or performance solely from a green unit test.

When a test cannot run, report why, preserve any static evidence, and give the exact command that remains to be executed.
