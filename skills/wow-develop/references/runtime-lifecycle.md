# Runtime Lifecycle Decisions

Use this reference for runtime ownership, startup, readiness, admission, draining, fatal failure, deadlines, and Spring shutdown.

## Stable invariants

- Give the runtime one lifecycle owner. Adapters may observe or delegate; they must not race to own start/stop transitions.
- Separate admission from readiness and from physical resource availability.
- Stop admitting new work before draining owned work.
- Preserve the first fatal cause and make later lifecycle signals consistent with it.
- Isolate deadlines and timers so one component cannot consume another component's shutdown budget.
- Make repeated start/stop/failure signals deterministic and safe.

## Discover the current implementation

```bash
rg -n "WowRuntime|RuntimeComponent|WowRuntimeLifecycle|GracefullyStoppable|DRAINING|readiness|fatal" . -g '*.kt' -g '*.java'
```

In this repository, inspect together:

- `wow-core/src/main/kotlin/me/ahoo/wow/runtime/`
- `wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt`
- lifecycle tests in `wow-core` and `wow-spring`
- `documentation/docs/zh/guide/advanced/runtime-lifecycle.md`

Use code and tests as authority when documentation differs.

## Verification boundary

Cover ordering and concurrency, not only happy-path completion:

- concurrent start/stop/fatal signals;
- admission before and after draining begins;
- readiness transitions;
- quiet-period and deadline exhaustion;
- failure preservation and suppressed/secondary errors;
- one slow or failed component not corrupting unrelated slots;
- Spring lifecycle phase and repeated shutdown behavior.

Green compilation or a single shutdown smoke test does not prove lifecycle correctness.
