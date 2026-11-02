<!-- DRAFT for review — not posted. Target: https://github.com/google/ksp/issues/new -->

# KSP2: concurrent `kspKotlin` tasks in one Gradle daemon intermittently resolve library types as errors, and processors silently generate less

## Environment

- KSP 2.3.13 (`ksp.useKSP2=true`, `ksp.incremental=true`), Kotlin Gradle plugin 2.4.21, Gradle 9.8.2, JDK 21 (Temurin), GitHub Actions `ubuntu-24.04` runners
- Large multi-module build (~100 projects), `org.gradle.parallel=true`, daemon `-Xmx2g`, the Gradle build cache read-only on PRs
- Our own processor (`me.ahoo.wow` compiler), loaded through the `ksp` configuration

## What happens

In CI, when many `kspKotlin` tasks run at the same time in one daemon (a change to the processor or a widely used module makes them all rerun instead of coming from the build cache), one of them sometimes behaves as if part of its library class path were missing:

1. **Library calls don't resolve.** In one run, `:migration-rehearsal:kspKotlin` reported Kotlin standard library extensions as unresolved (`map`, `any`, `apply` on a `List<…>`, `toBigDecimal` on an `Int`), as well as Jackson's `ObjectNode`. The module's sources didn't change, and five other `kspKotlin` tasks finished within the same few seconds.
2. **Annotation arguments come back unevaluated.** `getAnnotationsByType(...)` returns a proxy whose `String` member is `null` when the argument is a `const val` from another module, e.g. `@BoundedContext(name = OtherModuleConstants.NAME)`. The member has no default, so a null shouldn't be possible.
3. **Output silently shrinks.** Symbols whose types now resolve as error types fail `validate()`, so the processor defers them. Nothing more is generated in later rounds, and the processor ends having generated less than it should. Downstream modules then fail to compile with "Unresolved reference" on classes that should have been generated.

KSP itself logs no error or warning in these runs. A rerun of the same job usually passes, and we can't reproduce it locally with the same task graph, with or without the build cache and with warm and cold daemons.

## What we think is going on

Our understanding comes from reading the 2.3.13 bytecode. We haven't verified it against KSP's sources, so please correct us.

- `KspAAWorkerAction` runs KSP in the daemon through a cached class loader (`noIsolation`), so every KSP run in a daemon shares one IntelliJ application environment and its jar file system. Only the setup is guarded by `APPLICATION_LOCK`.
- The resolver, module, configuration and object caches are kept per thread (`ThreadLocal`).
- Several KSP runs overlapping in one daemon then appear able to see each other's environment or library state, so a run sometimes resolves library symbols as error types.

## Workaround

We now serialize the KSP tasks of a build. A shared `BuildService` with `maxParallelUsages = 1` is declared as used by every `kspKotlin` task. Only KSP runs one at a time; compilation and tests stay parallel. Since then <!-- fill in: "N runs without a miss" once we have data -->.

## Evidence

The repository is public, and the failing runs are linked below. We haven't minimized a reproducer yet.

Every case below is a fresh rebuild in which all `kspKotlin` tasks reran. The jobs carry diagnostic artifacts of the generated sources; they expire around 2026-11-04 05:10 UTC.

- **The standard library doesn't resolve.** In [run 41275556587, job "Check (rest)"](https://github.com/Ahoo-Wang/Wow/actions/runs/41275556587/job/124756950248), `:migration-rehearsal:kspKotlin` fails with KSP's own errors, around log lines 1068–1092:
  - `e: [ksp] Unresolved reference 'map'`, `'any'` and `'apply'`;
  - `'toBigDecimal' on receiver of type 'Int'`;
  - `Unresolved reference 'ObjectNode'`.

  In the same few seconds, five other modules' `kspKotlin` tasks finish.
- **A generated contract is missing its types.** In [the same run, job "Integration Test (eshop)"](https://github.com/Ahoo-Wang/Wow/actions/runs/41275556587/job/124756950379), `view-store-server:compileKotlin` can't resolve `ViewStoreContract`, `View`, `DatasetWording` or `ViewPreferences`. The upstream processor run generated nothing for them.
- **A processor's output is empty.** In [run 41276227933, job "Check (host)"](https://github.com/Ahoo-Wang/Wow/actions/runs/41276227933/job/124758886444), artifact `x54-diagnostics-check-host-1`:
  - the payment domain's generated manifest fragment has its context but 0 aggregates and 0 subscriptions, where a local build of the same commit has 1 and 5;
  - the generated model provider lists no models and sits in the root package.

  The aggregate and saga classes never passed `validate()`, and were deferred to the end and dropped, with no message.
- **A symbol using a library type is dropped.** In [the same run, job "Check (apps)"](https://github.com/Ahoo-Wang/Wow/actions/runs/41276227933/job/124758886383), a service route whose function takes Jackson's `ObjectNode` is missing from the generated output. Our compatibility check reports it as removed (artifact `x54-diagnostics-check-apps-1`).

## Questions

- Is running several KSP2 invocations concurrently in one daemon supported?
- If it is, is there a setting that isolates them (we found `ksp.classloader.cache.processors` and `ksp.project.isolation.enabled`, but nothing per run)?
- If it isn't, could KSP serialize its own work, or at least fail loudly when its library class path can't be read, rather than resolving silently to error types?
