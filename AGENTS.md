# Wow - Agent Instructions

## Build & Run

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew <module>:clean <module>:check --stacktrace
./gradlew :check
./gradlew :integrationTest
./gradlew detekt
./gradlew detekt -Pwow.detekt.autoCorrect
./gradlew build
./gradlew checkKotlinAbi
./gradlew updateKotlinAbi
```

Use Gradle module paths from `settings.gradle.kts`, for example `:api`, `:kernel`, `:runtime`, `:spring-boot`, `:test-dsl` and `:eshop-catalog-domain`. `main` holds 10.x only: the 9.x code (the `wow-*` modules, `test/wow-*`, the compensation, view-store, dataset-registry and analytics services, the 9.x examples and JMH benchmarks) left it early (decisions D244, D262) and lives on `v9`, the 9.x line, and on the side branch `v10-9x-baseline`, the last commit with both; 9.x benchmark baselines and 9.x reference reads come from there. The eShop's services run as its README describes (`./gradlew :eshop-catalog-server:run`, ...).

JavaScript projects share one pnpm workspace rooted at the repository root (`package.json`, `pnpm-workspace.yaml`, one `pnpm-lock.yaml`). Install once from the root; TypeScript packages under `typescript/` follow [typescript/AGENTS.md](typescript/AGENTS.md).

```bash
pnpm install
```

Ops console (`platform/ops/console`, served by `:ops-server` from its `ui/`; see its README for the end-to-end suite):

```bash
pnpm --filter wow-ops-console^... build
cd platform/ops/console
pnpm test
pnpm lint
pnpm build
```

Documentation site:

```bash
cd documentation
pnpm docs:dev
pnpm docs:build
pnpm docs:preview
```

## Testing

The JVM code uses JUnit Jupiter through JUnit 6, MockK, Reactor test support, and `me.ahoo.test:fluent-assert-core`. Prefer the FluentAssert `.assert()` extension in Kotlin tests instead of AssertJ `assertThat()`.

Choose the test layer that covers the affected behavior:

| Source set | Module task | Repository task | Runtime requirements |
| --- | --- | --- | --- |
| `src/test` | `<module>:test` | `allLocalTest`, `:check` | No containers |
| `src/integrationTest` | `<module>:integrationTest` | `integrationTest` | Docker/Testcontainers |

`check` includes `test`, but does not run `integrationTest`. For storage or broker changes, run the affected module's integration tests explicitly. Use only layers registered for that module in `build.gradle.kts`.

```bash
./gradlew :kernel:test
./gradlew :eshop-catalog-domain:check
./gradlew :mongo:integrationTest --stacktrace
./gradlew :eshop-e2e:integrationTest --stacktrace
```

Domain tests use the test DSL (`:test-dsl`, artifact `wow-test`); the eShop's domain modules show it.

CI sets `CI=GITHUB_ACTIONS`; Gradle test retry is enabled only in CI with up to 2 retries and 20 max failures. Domain modules enforce Jacoco coverage, commonly 80% minimum.

Coverage measures tested behaviour; it never decides a code shape. Do not change a declaration to move a coverage number: not `inline` versus non-inline, not merging, removing or reordering branches, not restructuring for JaCoCo. Decide each shape on its design (an `inline` function, for one, runs its lambdas in the caller's context, suspending or blocking, and allocates none). A test that executes a line without asserting behaviour does not count. When a coverage gate fails only because the tool cannot see code (an `inline` or `reified` body, which JaCoCo attributes to no line, or a branch only container tests reach), cover the behaviour with real tests, and if the tool still cannot credit them, exclude exactly that file in `codecov.yml` with a comment naming the limit and the test that covers it; the coordinator decides each exclusion.

## Project Structure

```text
typescript/                 TypeScript client packages moving in from fetcher; see typescript/AGENTS.md
documentation/              VitePress documentation site (docs/) and design records (designs/)
skills/                     Project-local Codex skills and agent definitions
```

The modules ([modules design](documentation/designs/06-modules.md) §4):

```text
bom/bom/                    :bom            The BOM, artifact `wow-bom`
bom/dependencies/           :wow-dependencies The slim platform every POM but the contract modules imports: Jackson, coroutines, kotlin-logging, SLF4J (D287)
api/annotations/            :annotations    The stability markers: InternalWowApi, ExperimentalWowApi, WowSpi; no dependencies
api/api/                    :api            The programming model: annotations, Decision, DecideScope, StateEvent, Scope
api/manifest/               :manifest       The Manifest every projection reads; plain data
api/query-api/              :query-api      Query protocol: filters, aggregation, windows
compiler/compiler/          :compiler       KSP: aggregate models and Manifest fragments from the annotations
compiler/gradle-plugin/     :gradle-plugin  Gradle plugins `me.ahoo.wow` (domain), `me.ahoo.wow.api`, `me.ahoo.wow.service` (Manifest)
compiler/manifest-tools/    :manifest-tools Completes Manifest fragments with schemas and merges them
core/spi/                   :spi            Ports a backend implements: log, snapshots, checkpoints, leases, ledger
core/kernel/                :kernel         Aggregate model and the decide, evolve, append loop
core/runtime/               :runtime        Gateway, router, cluster channel, executor, subscriptions, waits
core/reactor/               :reactor        Reactor adapters for the programming model
backends/postgres/          :postgres       SPI on PostgreSQL
backends/mongo/             :mongo          SPI on MongoDB
edge/http/                  :http           Routes from the Manifest, problem+json, SSE
edge/cosec/                 :cosec          CoSec: the caller from CoSec's security context, command authorization
host/spring-boot/           :spring-boot    Spring Boot auto-configuration; wiring only
migration/migrate-data/     :migrate-data   Imports 9.x event streams into the log
test/test-dsl/              :test-dsl       The test DSL, artifact `wow-test`: aggregate and processor specs on the kernel
test/test-query/            :test-query     A real in-memory QueryApi for the test DSL's processor specs (`memoryQueries`)
test/test-spring-boot/      :test-spring-boot  A Wow application started for a test (`wowServer`), its ports and HTTP client
test/architecture/          :architecture   Module graph and dependency rules
test/routing-spike/         :routing-spike, test/lease-spike/ :lease-spike: spikes H2, H6
test/benchmarks/            :benchmark-server, :benchmark-search-server, :benchmark-load: the end-to-end benchmark (D224; harness/, bench.yml) and the performance reports (results/)
examples/eshop/<context>/    :eshop-<context>-{api,domain,server}: the example, the eShop's five contexts (D159, D204); never published
examples/eshop/e2e/          :eshop-e2e: the eShop's services end to end on PostgreSQL, Kafka and ClickHouse
```

- Gradle project names and directories carry no `wow-` prefix (`:kernel` in `core/kernel`); the root build sets their jar name and Maven artifactId to `wow-<name>` (D87). `:architecture` forbids including or depending on a deleted 9.x project (`DELETED_9X`).
- A release publishes the modules (R.1; see Releasing): `abiProjects` plus `:migrate-data` and `:manifest-tools`, the BOM `:bom` (`bom/bom`, artifact `wow-bom`, constraining exactly those) and `:wow-dependencies` (`bom/dependencies`), which every POM but the contract modules' imports; the platform services, `:architecture`, the spikes and the eShop stay unpublished (`incubatingProjects`). Their ABI is frozen at the M7 exit (D88, D147, D216): `abiProjects` in `build.gradle.kts` (every published Wow module except `:migrate-data` and `:manifest-tools`) joins the `abiValidation` block, keeps its dump in `<module>/api/<module>.api` (recorded by D216 F.5), and `:check` runs its `checkKotlinAbi`; `./gradlew updateKotlinAbi` rewrites the dumps. Between the M7 exit and 10.0.0 GA a dump is a review tool, see Binary Compatibility.
- API levels (D216): a declaration only its own module uses is `internal`; one Wow's modules share but promise nothing about is `@InternalWowApi` (an `ERROR`-level opt-in, left out of the dumps; mark a constructor to hide only how a public class is built); a surface 10.x may still change (dataset queries, aggregation queries and their expressions, the query schema declarations, the HTTP ops routes and webhooks, Spring Cloud peer discovery, and `wow-cosec` with the host's CoSec auto-configurations while CoSec 8 is a pre-release, D374; [API Levels](documentation/docs/en/reference/api-levels.md) lists each declaration) is `@ExperimentalWowApi` (a `WARNING`-level opt-in, kept in the dumps). Only Wow's own modules opt in to both; the eShop, the platform services' domains and servers and `:architecture` (`userProjects`) do not, so they compile against the public API as a user does: opt in with `@OptIn(ExperimentalWowApi::class)` where they use an experimental surface, and never opt in to `InternalWowApi`. Access attributes and access policies (`@Access`, `AccessPolicy`, `accessPolicy`) and read authorizers (`ReadAuthorizer`) are public API, with no marker. Wow modules compile with `-jvm-default=no-compatibility` (no `DefaultImpls` classes).
- The build applies the real Gradle plugins (D363 ⑤): `settings.gradle.kts` includes `build-logic/wow-gradle-plugin`, which compiles `compiler/gradle-plugin`'s sources and publishes nothing, so the eShop, the platform services and the test applications apply `me.ahoo.wow`, `me.ahoo.wow.api` and `me.ahoo.wow.service` as a user's build does. They declare Wow by its coordinates (`"me.ahoo.wow:wow-spring-boot"`), which the root build substitutes with the projects; a module of the same service (its api, its domain) stays a `project(...)`. `:gradle-plugin` is still the project that is tested, checked and published. `publicationConsumerCheck` builds `test/publication-consumer`, the documented three-module layout, against what `publicationCheck` published.
- Building them requires JDK 21 (D29).
- `./gradlew :check` compiles the modules, runs their unit tests (`:architecture:test` included), detekt and the ABI checks, and `./gradlew :integrationTest` runs their container-backed tests; `:code-coverage-report:coverageReport` and `integrationCoverageReport` are their coverage. The build scope `-Pwow.scope` is gone with the 9.x projects.


## Code Style

- Kotlin and KSP versions come from `gradle/libs.versions.toml`; JVM toolchain 21 is configured in `build.gradle.kts` (decision D29), and `kotlin.code.style=official` in `gradle.properties`.
- Published modules import only the slim `wow-dependencies` (D287), and the contract modules (`coreContractProjects`: `:annotations`, `:api`, `:query-api`, `:manifest`) not even that: no platform but the coroutines BOM, and no SLF4J; the Spring Boot BOM is `wow-spring-boot`'s `api(platform)`. The repository build resolves every classpath through `wowBuildPlatform` in the root build (the Spring Boot BOM, the test and lint platforms), which is never published; a published POM names the versions it resolved (`versionMapping`), and `publicationCheck` rejects any other platform import.
- All JVM packages live under `me.ahoo.wow`; examples use `me.ahoo.wow.example`.
- Source files use the Apache 2.0 copyright header already present in the repository.
- The core is coroutines (`suspend`, `Flow`); a domain's handlers are `suspend`, synchronous, or Reactor through `:reactor`. Do not block a thread in the core's paths: a blocking handler is marked `@Blocking` and runs on a virtual thread, and a blocking driver runs on its backend's own dispatcher.
- Prefer focused interfaces and module boundaries (06-modules.md §3, held by `:architecture`): the programming model in `:api`, the decide/evolve/append loop in `:kernel`, the node in `:runtime`, Spring wiring in `:spring-boot` only, and storage or transport concerns in their backends.
- Serialization uses Jackson; framework code uses the existing Jackson stack and Spring compatibility modules where already present.
- ID generation uses Wow's ID helpers (`GlobalIdGenerator`, `AggregateIdGenerator` in `:spi`): the node's own ids (request, command, wait, ledger) are always UUIDv7; CosId only gives aggregate ids, through `wow-cosid`.
- Logging uses kotlin-logging, SLF4J, and Logback.
- Detekt uses `config/detekt/detekt.yml`; line-length and several style rules are intentionally relaxed.
- `inline` (and `reified`) is a design choice, made where the function needs the caller's context for its lambdas (a helper called from suspending and blocking code alike), needs a reified type, or must not allocate its lambdas on a hot path; never to satisfy, or to avoid, a coverage tool (see Testing).

Typical Kotlin API style, a port of `:spi`:

```kotlin
@WowSpi
interface StoreProvider {
    val type: String
    val requirement: String get() = "its resources"
    fun available(resources: StoreResources): Boolean = false
    fun open(config: StoreConfig): Store
}
```

The apps (`platform/ops/console`, `examples/eshop/web`) use React, TypeScript, Vite, shadcn/Base UI, Tailwind CSS, React Router, Vitest, and ESLint; dependency versions live in the `catalog` of the root `pnpm-workspace.yaml`, and each app's `components.json` holds its shadcn configuration. Their Wow clients are generated under `src/generated/` by `wow-generator` (`@ahoo-wang/wow-generator`) from the services' Manifests (each app's `generate` script); commit the generator output byte for byte, including `.wow-generator.json`, and never hand-edit it.

## Version Management

The project version is the `version` property in `gradle.properties`. Maven and npm release together under that version and one `v<version>` tag. Third-party versions are centralized in `gradle/libs.versions.toml`, the build platform `wowBuildPlatform` and the `wow-dependencies` module.

A release is prepared in one pull request (`chore(release): prepare <version>`):

1. `pnpm set-version <version>` writes `gradle.properties`, the version of every `typescript/*/package.json`, `platform/ops/console/package.json` and `documentation/package.json`, and the `version` and `appVersion` of the Helm chart (`deploy/helm/wow-service/Chart.yaml`). It then lists the tracked files that still mention the old version.
2. Update the ones that track the release by hand: `documentation/docs/{en,zh}/start/quickstart.md`, `existing-project.md` and any other docs page that pins the version. The docs are the Wow 10 docs, so they move with every 10.x release, pre-releases included; the 9.x docs live on `v9`. The README shows no Wow version, the services' `manifest/baseline.json` (only its compatibility line, the major) and the OpenAPI snapshots (`edge/openapi/src/test/resources/openapi/`) record none, and the design records under `documentation/designs/` keep the versions they name.
3. `pnpm check:versions` confirms every `package.json` matches; the `quality` job of `ci.yml` runs the same check.

Breaking changes, Kotlin or TypeScript, ship only in an `x.Y.0` release. A change breaks when it changes public API (Kotlin, or a published npm package), REST behaviour, configuration, storage or wire formats; mark it with `!` in the conventional commit, or tick the PR template's **Breaking** box (it labels the PR `breaking-change`), and give the description a `## Breaking` section saying who is affected and how to migrate. A `## Behaviour changes` section is for user-visible changes that are not breaking; it adds no label. Release admission enforces this, and requires the x.Y.0 release notes to name each such PR. Wow keeps no compatibility code (decision D1): a replaced API goes in the same release instead of being deprecated, and `:architecture` rejects `@Deprecated` declarations and `compat(...)` markers.

### Binary Compatibility

Every published Maven module except the BOMs (`publishProjects` minus `bomProjects` in `build.gradle.kts`) keeps its public JVM ABI in `<module>/api/<module>.api`: classes, constructors, methods and fields with their JVM signatures, synthetic overloads included. Declarations marked `@InternalWowApi` are left out; `@WowSpi` declarations (the storage, query and transport ports, an opt-in with a compiler warning) stay in the dump. An interface member marked `@InternalWowApi` also drops out of the dumps of the classes in other modules that implement the interface. The dumps come from Kotlin's built-in ABI validation (`abiValidation` in the Kotlin Gradle plugin); `:check` (ci.yml) depends on `checkKotlinAbi`, so a pull request that changes a public signature without its dump fails.

When the check fails, run `./gradlew updateKotlinAbi` (or `./gradlew :<module>:updateKotlinAbi`) and commit the rewritten dumps with the change. Read the dump diff before committing it:

- Only `+` lines (a new class, member or overload): additive, allowed in any release, patches included.
- Any `-` line, or a changed one (a removed or renamed declaration, a parameter added to a constructor or function even with a default value, a changed return type): a binary break. From 10.0.0 GA it ships only in an `x.Y.0`, marked breaking as above, with a `## Breaking` section naming the removed signatures and what callers use instead. Wow keeps no compatibility code (decision D1): a break changes the signature cleanly, with no hidden overload, `@Deprecated` bridge or `compat(...)` marker kept for old callers, which `:architecture` rejects.

A module that starts publishing commits its first dump in the same pull request; the check fails while a published module has none.

The ABI dumps (D216) follow these rules only from 10.0.0 GA. Between the M7 exit and GA they are a review tool: a `-` or changed line is allowed, but the pull request carries the `breaking-change` label and says in its description which signatures went and why; the dump diff is the record. `@ExperimentalWowApi` declarations stay in the dumps, so their changes show too, and a reviewer accepts their `-` lines as expected. A known gap of the Kotlin Gradle plugin (2.4.21): a class whose `@InternalWowApi` primary constructor has default arguments and a value-class parameter (`kotlin.time.Duration`: `WaitPlan` and the runtime, Kafka and ClickHouse `*Options`) keeps that constructor's synthetic default-argument overload (`public synthetic fun <init> (…ILkotlin/jvm/internal/DefaultConstructorMarker;)V`) in its dump; those lines are accepted and change whenever the constructor does.

`pr-labeler.yml` applies the same rule to the diff: a pull request that removes or changes a line of a Kotlin dump (`**/api/*.api`), a TypeScript API report (`typescript/*/test/api/*.api.md`) or a surface list (`typescript/*/test/surface/*.txt`) is labelled `breaking-change`, which keeps it out of a patch release; `+` lines alone add no label. Blank lines, a surface list's `#` header, an API report's `//` comments and lines that only moved or were re-indented within the same file do not count (`.github/scripts/breaking-label.mjs`). When a changed line is not a break, a maintainer removes the label after the last push (the labeler runs again on every push and adds it back) and says why in the pull request.

## CI And Release Workflows

GitHub Actions run module-level checks from `.github/workflows/`:

- `ci.yml` is the one CI workflow, on every pull request to and push to `main`. Its scope job (`.github/scripts/ci-scope.mjs`) decides from the changed paths which areas run: the JVM jobs (`jvm`: the modules, their tests, the examples, `bom/`, the root build and Gradle configuration) run `:check` in four shards (`-Pwow.check.shard`: `host`, `apps`, `tooling` and `rest`, which takes every module the others do not name, from `checkShards` in `build.gradle.kts`; `rest` also runs `publicationCheck`) and `:integrationTest` in four shards, both balanced by measured durations (`-Pwow.integration.projects`; `.github/scripts/integration-shards.mjs` checks every integration test project is in exactly one shard, that the integration matrix's `shard` list and its `include` entries agree, and that the check matrix runs each check shard once), with the Gradle cache written only by pushes to `main`; both matrices cross their shards with a `java` leg, 21 on a pull request and 21 and 25 elsewhere: a JDK 25 leg compiles with the toolchain's 21 as always and runs the tests on Temurin 25 (`-Pwow.test.java=25`, found through `JAVA_HOME_25_X64`; D29, D368), uploads no coverage and rehearses no publication; the TypeScript jobs (static checks, the unit matrix, the package checks, the fetcher floor, view-engine's shards, the site), the Storybook jobs (build, interactions in Chromium in four shards balanced by `typescript/storybook/test-durations.json`, the screenshot baselines), the view store contract (the view store suites against the service built from the commit), the eShop web app, the ops console and the analytics catalog console (each with its end-to-end suite), the docs layout and repository hygiene (`pnpm check:hygiene`, `.github/scripts/repo-hygiene.mjs`: no version catalog entry that no build script uses, no `.gitignore`, `.prettierignore` or labeler path that matches nothing, no CI script that nothing runs; a known leftover goes in its `ALLOWED` with the change that removes it), actionlint over every workflow, and the `wow-service` Helm chart (`deploy/helm/**`, `.github/helm/**`; `.github/helm/chart.sh`): `helm lint --strict`, its helm-unittest suites, the schema's refusals and kubeconform over every CI value set and an umbrella chart, on Helm 3 and 4, and an install on kind. A docs-only or TypeScript-only change skips the JVM jobs; the architecture job then runs, on their behalf, `:architecture:test` when it touches a page or README the design-ID rule reads, `:http:test --tests '*DocumentedQueriesTest'` when it touches a query example that test reads (the user documentation, the skills, the READMEs under `typescript/` and `examples/`), and `:spring-boot:test --tests '*ConfigurationKeysTest'` when it touches a YAML file of `deploy/`, whose `WOW_` variables must be keys the host knows (the scope's `architecture`, `documentedQueries` and `deployKeys`). `ci-gate` is the merge signal: it passes when every job that ran passed, and, on a pull request whose run has coverage (the JVM check or integration shards, the TypeScript units or view-engine), when the coverage job's patch verdict does not fail. The test jobs keep their reports as artifacts; the coverage job judges the main-source lines the pull request adds or changes against its merge base with main from those reports (the JaCoCo XML without its closing-brace lines, each package's Istanbul JSON; `codecov.yml`'s `ignore` left out): covered ÷ instrumented must be at least 89% (Codecov's 90% target less its 1% threshold), and the job summary lists every uncovered line (`.github/scripts/patch-coverage.mjs`). A changed main-source file no report instruments is a warning (no executable line, or a module outside the coverage aggregation), and a failure when no report of its kind ran at all. A run without coverage leaves the patch out. On the nightly's full run on main, `coverage-trend` compares the commit's Codecov coverage, overall and per flag, with the nightly of a week earlier and fails on a drop of more than 0.5 points overall or 1 point on a flag (`.github/scripts/coverage-trend.mjs`). `main`'s branch protection requires `ci-gate` and PR Safety. A manual dispatch runs everything (`-f update-screenshots=true` takes new Storybook baselines). The Node 22 jobs (the unit matrix's Node 22 legs, view-engine's Node 22 shards and `Package / Node 22.12.0`) and the JVM jobs' JDK 25 legs (`Check (<shard>) / JDK 25`, `Integration Test (<shard>) / JDK 25`) run on pushes to `main` and on dispatch, not on pull requests, to spare runners; a JDK 25 failure on the nightly's run opens its issue like any other. `nightly.yml` (a schedule runs from the default branch, `main`) dispatches the eShop's soak (`soak.yml`), the MongoDB clusters (`mongo-cluster.yml`), the GA end-to-end run (`ga-e2e.yml`) and a full `ci.yml` run on `main` every night that `main` moved. `nightly-issues.yml` reports them (D375): GitHub emits no `workflow_run` for a run the `GITHUB_TOKEN` dispatched, so a sweep on a schedule after the nightly window (01:30 and 06:30 UTC) and on dispatch reports each one's latest completed dispatch run on `main` in the last 36 hours, and `workflow_run` (also run from `main`) reports a manual dispatch as it completes. A failed dispatch run of one of the four on `main` opens that workflow's issue (label `nightly-failure`, title `Nightly: <workflow> failed`) or comments on the open one with the run, its commit and its failed jobs, once per run id, its next green run comments and closes it, a cancelled or skipped run changes nothing, and only its one job has `issues: write` (`.github/scripts/nightly-issues.mjs`; `--sweep` sweeps, `--dry-run` prints what it would do). The 9.x Gradle workflows left `v10` with the 9.x code (D262) and run on main.
- `ci.yml`'s `axon-server` job tests and builds the industry comparison's Axon server (`test/benchmarks/axon-server`, a Gradle build of its own, outside the repository's: `./gradlew -p test/benchmarks/axon-server test installDist`) when its paths or `ci.yml` change; `ci-gate` takes it skipped as passed.
- `ci.yml`'s `migrate-data image` job builds the `wow-migrate-data` image (`migration/migrate-data/Dockerfile`) from the commit, never pushed, and runs it as a user does: `--help` as its non-root user with the heap of its memory limit, and `check` on a read-only root over a MongoDB holding one 9.x stream, with the fixture `migration/migrate-data/src/test/resources/image-smoke` (which `ImageSmokeFixtureTest` keeps passing) mounted at `/config`. The `migrateDataImage` scope turns it on (`migration/migrate-data/**`, `deploy/migrate-data/**`, the workflow), and `ci-gate` needs it.
- Codecov (`codecov.yml` at the root) is informational: it reports pull requests from the `jvm-unit` flag (the check shards' reports), the `integration` flag (one upload per shard) and the TypeScript packages' flags, with a patch status (90%), a project status and one component per module and TypeScript package, all `informational`, so none blocks a pull request, and ci.yml's uploads and its one notification trigger (sent after every upload, `manual_trigger`) cannot fail the run. A module's coverage counts its unit and container tests, so storage adapters' I/O is covered by their container tests; no flag has `paths`, which would widen every component to the whole flag.
- `documentation-deploy.yml` deploys the docs on a push to `main` that changes what the site shows, nightly (which is what brings the `v9` branch's pages to `/9.x/`) and by hand. `image-deploy.yml`, called by `package-deploy.yml`, pushes the server images and the `migrate-data` image (DockerHub `ahoowang/`, GHCR `ghcr.io/ahoo-wang/`, Aliyun `registry.cn-shanghai.aliyuncs.com/ahoo/`), and `chart-deploy.yml`, called beside it, the `wow-service` Helm chart to the same three registries as an OCI chart (`oci://ghcr.io/ahoo-wang/wow-service`, `oci://registry-1.docker.io/ahoowang/wow-service`, `oci://registry.cn-shanghai.aliyuncs.com/ahoo/wow-service`) under the release's version alone. The 9.x image workflows (example, compensation, view store, analytics) left with the 9.x code (D262) and run on `v9`.
- `pr-safety.yml` (PR Safety, required by `main`'s branch protection with `ci-gate`) runs `.github/scripts/pr-safety.sh` from the base commit on `pull_request_target`, so a change to the script takes effect once it is on `main`. It fails a pull request that deletes a tracked file when it is a dependency PR (a `renovate/` branch or the `dependencies` label), changes more than 3,000 files (GitHub exposes no more for inspection), gets incomplete file data from GitHub, finds an empty base tree, or removes more than 10% of the base's tracked files. The `allow-large-removal` label lifts only the last rule, for a deliberate cleanup: a maintainer applies it by hand, after a review confirms the removal is intended, never a bot or the author's own say-so (`.github/labeler.yml` and `pr-labeler.yml` never apply it, which `.github/scripts/pr-safety.test.mjs` holds). With it the job warns instead of failing and writes the removed and base counts to its summary; every other rule stays a hard failure, and the workflow re-runs on `labeled` and `unlabeled`, so removing the label fails the rule again. `pnpm test:ci-scripts` runs the script's `--self-test`.
- `pr-labeler.yml` labels pull requests by branch and changed paths (`.github/labeler.yml`), and adds `breaking-change` to a title of the form `type(scope)!: …` or a description with a `BREAKING CHANGE:` line, the template's **Breaking** box ticked, or a `## Breaking` / `## Breaking changes` section that is not "None.", and to a diff that removes or changes a line of an ABI dump, API report or surface list (see Binary Compatibility; `.github/scripts/breaking-label.mjs`); `.github/release.yml` lists breaking changes first in generated release notes.
- `typescript-storybook-browsers.yml` runs the same story interactions in Firefox (two shards) and WebKit (four shards, on macOS), nightly and on `workflow_dispatch`. It never runs on a pull request, is not a merge signal, and release admission does not read it.
- `package-deploy.yml` publishes when a GitHub Release is created, or when it is dispatched by hand on a `v*` tag; any other ref is refused. Every action in it is pinned to a commit SHA. `admission` checks that the tag, `gradle.properties` and every `package.json` agree and runs release admission (`.github/scripts/release-admission.mjs`). `preflight` builds, packs and checks the npm tarballs (`.github/scripts/package-check.mjs`), dry-runs the publish, and runs the Gradle build and integration tests. Then `github-deploy` (GitHub Packages) and `central-deploy` (Maven Central) run in parallel; once both succeed, `npm-deploy` publishes in the `npm-publish` environment (`v*` tags only, no reviewer; npm's trusted publisher is bound to it). After it, `npm-smoke` (`package-check.mjs --registry`, on Node 22.12.0 and 24) waits a bounded time for npm to serve the version, checks the dist-tags, and installs, imports, requires and type-checks the published packages in a clean project.
- Release admission requires the release commit to be on `main` or a `release-x.y` branch (9.x releases from `v9`, with its own copy of the script), and a successful `workflow_dispatch` run of `ci.yml` on that commit with `ci-gate` passing (a dispatch run turns every scope on; push runs do not count). It dispatches the run a commit lacks and waits for them. It refuses a patch release when any commit since the previous `v*` tag is breaking (`!`, a `BREAKING CHANGE:` footer, or a pull request labelled `breaking-change`), and refuses an `x.Y.0` whose GitHub release notes do not name every such pull request (`#number`) on the release branch.
- `npm-deploy` publishes the tarballs `preflight` packed, through `.github/scripts/publish-npm.mjs --tarballs`, without installing or building. It publishes every package in `PUBLISHED` (from 9.2.0: `@ahoo-wang/wow-client`, `@ahoo-wang/wow-react`, `@ahoo-wang/wow-generator`, `@ahoo-wang/wow-view-engine` and `@ahoo-wang/wow-view-store`) with OIDC trusted publishing and provenance, skips a version already on npm (so a failed run can be re-run), and tags a patch to an older line `release-<major>.<minor>` instead of `latest`. A real publish refuses a dirty working tree or a HEAD that is not the commit of `v<version>`. Private packages are never published. A new public package must be added to `PUBLISHED` or `HELD_BACK` in that script. Maven modules that are not ready to publish go in `incubatingProjects` in `build.gradle.kts` (empty today: the dataset registry left it with the analytics service); `wow-bom` constrains only the published modules. The maintainer runbook, including the first npm release, is `typescript/RELEASING.md`.

Before changing release or publish behavior, inspect the workflow and Gradle publishing configuration together.

### Releasing

On `main`, `package-deploy.yml` releases the 10.x line, stable (`v10.x.y`) and pre-release (`v10.x.y-alpha.n`, `-beta.n`, `-rc.n`; D202, D217, D265); a tag of an older major is refused, since 9.x releases from `v9`. Its gates are `:check :integrationTest` and `publicationCheck -Pwow.publication.signed=true`, and the Maven jobs publish the modules (the build has no 9.x module). `.github/scripts/release-plan.mjs` decides the rest from the tag and every `v*` tag, for both `package-deploy.yml` and `image-deploy.yml`, which pushes `wow-ops-server`, `wow-view-store-server`, `wow-analytics-catalog-server` and `wow-migrate-data`:

- A pre-release: npm publishes under `alpha`, `beta` or `next` and `publish-npm.mjs` refuses `latest` for any pre-release; the images get the version alone; the GitHub release is a pre-release, never latest, with `.github/release-notes/prerelease.md` (no compatibility promise; 9.x data migration is a rehearsed pre-release tool, not for production data; not for production). A pre-release carries no compatibility promise.
- A stable release on the latest line (the highest stable `v*` tag): npm publishes under `latest`; the images get the version, `X.Y` and `latest`; the GitHub release is not a pre-release and is marked latest, with `.github/release-notes/stable.md` when none exists. An `x.Y.0` with breaking changes needs its GitHub release, naming each of them, before admission.
- A stable patch to an older line: npm publishes under `release-X.Y`; the images get the version and `X.Y`; the GitHub release is not marked latest.
- A floating image tag (`X.Y`, `latest`) only ever moves to the highest stable version of its line among all `v*` tags, whichever tag the workflow runs on: a re-run of an older patch, after a later patch of its line was tagged, pushes its version alone.

Steps:

1. Rehearse: `./gradlew :check` and `./gradlew -Pversion=<version> publicationCheck` (signs, with a throwaway GnuPG key unless `-Pwow.publication.signed=true` gives it the release key, so it needs `gpg`; publishes into `build/publication-check/repo` and checks every jar, sources and javadoc jar, POM, module metadata and their signatures, the BOM and that nothing else is published, no extra classifier such as a test-fixtures jar either; then runs the deploy jobs' publish as they do, `-Pwow.publication.prebuilt=true` with CI set and no `SIGNING_*` key, which uploads the preflight's files and signatures without rebuilding or re-signing them, into `build/publication-check/deploy`, and requires that it runs nothing but publish tasks, uploads the same files byte for byte and that every signature verifies; `ci.yml` runs it with the throwaway key on every pull request), `pnpm test:ci-scripts`, `node .github/scripts/package-check.mjs`.
2. Prepare the version in one pull request to `main` (`chore(release): prepare <version>`): `pnpm set-version <version>` (gradle.properties and every versioned `package.json`; `v9` keeps its 9.x version), then move the docs pages that pin the version to it (Version Management, step 2).
3. Dispatch the full `ci.yml` run release admission needs on the merged commit, or let admission dispatch it.
4. **The user confirms first** (D124, D202): push the tag `v<version>` on the merged `main` commit.
5. Dispatch `package-deploy.yml` on that tag ("Use workflow from" → the tag), or create its GitHub release by hand. It admits, gates, publishes Maven, then npm, smoke-tests npm, pushes the images and the Helm chart, and creates or marks the GitHub release as the plan says; edit the "What is in it" section of a release it created afterwards.

## Boundaries

- Always run the narrowest relevant Gradle, pnpm, or docs command before reporting a change as complete.
- Always add or update tests when changing command handling, event sourcing, projections, sagas, compensation behavior, serialization, schema generation, or generated metadata.
- Always preserve public API compatibility unless the user explicitly asks for a breaking change.
- Prefer clean source and the smallest correct implementation. Do not add compatibility bridges, custom serialization or creators, duplicate validation, or tests of dependency behavior without a concrete requirement or reproduced failure.
- Do not add `@JsonCreator` factories solely to reproduce Kotlin constructor defaults. Configure the Kotlin Jackson module instead; reserve creators for non-object wire shapes or Java constructors and cover them with contract tests.
- Treat source, binary, and wire compatibility as separate scopes. Implement only the compatibility scope the user requested; do not infer binary or wire compatibility from source compatibility.
- Trust installed framework modules and backend-native semantics. Add validation only for the public contract, security, data-loss prevention, or a demonstrated backend requirement.
- Ask first before changing Gradle module structure, feature capabilities, generated OpenAPI/schema contracts, CI/CD workflows, publishing credentials, or release automation.
- Ask first before adding dependencies or moving responsibilities across module boundaries.
- Never commit secrets, signing keys, Maven credentials, GitHub tokens, generated build output, `node_modules/`, `.gradle/`, or IDE-local state.
- Never edit generated clients in an app's `src/generated/` as the primary fix when the Manifest or the generator can be fixed instead.
- Never block a thread in the core's runtime paths.

## Working Method

How work is planned, reviewed and merged when several agent sessions work on the repository at once, and what a review holds a change to. Every rule here was paid for by a failure in this repository; the pull request beside a rule is where. This section is the one copy of these rules; [progress.md](documentation/designs/progress.md) §5 holds what each review criterion means here.

### Sessions

- One coordinating session plans, dispatches, reviews, merges and edits the shared records (`documentation/designs/progress.md`, `reviews.md`, `decisions.md`, this file). The records hold what is still of use: where the work stands, what is open, what was decided and why. A finished item leaves the progress, roadmap and review records when it is done, and git keeps what they said (the user's decision, 2026-11-10). Implementation goes to sub-sessions, each with one area and a brief that names the files, what done looks like and the rules of this section. A sub-session edits no shared record and no file of another session's area.
- At most five sub-sessions have work at a time. Count before dispatching, and count a session until its last pull request has merged: one that only waits for CI still holds its place.
- Work on one file is serial: one open pull request per file or chain of files, at most three open per session. Independent chains (two stores, a worker and its runtime) run side by side.
- A design comes before code wherever a change spans more than one pull request. The session writes a note, kept outside the repository: what each file holds, what moves and why, what stays whole and why, the invariants a move must keep with the test that holds each, the order of pull requests and what will be measured. The coordinator decides it, and pull requests wait for the decision.
- A next task, a go-ahead, a decision and a review that asks for a change go to the session by direct message. Only an approval may rest on the pull request's comment alone, and the notice that it merged is still sent directly: nobody is woken by a comment, with Auto-fix on or off (a session that answered a coverage bot's comments twice reported "waiting for your review" under the coordinator's on #5891). Two sessions once sat idle for an hour on decisions written on merged pull requests, and a third reported "waiting for your review" under a review that asked for one change (#5781). After a wave of merges, check that each session that should have started something did.
- A session stops before a choice that is not its own and says what it found (a rule switched off in a script, a fix that would change behaviour in a move, a figure it cannot explain); the coordinator decides. A session never does for another what that session's permissions refused: it tells the user.
- Report what was observed, not what was expected. A pushed head is quoted from `git ls-remote` after the push, a pull request number from the command's output, a test count from the run that produced it. Twice a reported hash was wrong, and only the guard on the merge caught it.
- When a figure or a claim you reported turns out wrong, say so unprompted and correct the record. A coverage figure measured from an overwritten report was corrected by the session that had reported it, and an explanation of a benchmark difference by the reviewer who had given it; each time the decision already taken was checked again against the corrected fact.

### Pull requests

- Commits and pull request titles are conventional commits in English, with no tool or AI attribution.
- A session opens its pull request as a draft and turns Auto-fix on for it at once, so that CI failures, conflicts and review comments wake it.
- The coordinator reviews the head. On approval it marks the pull request ready and turns auto-merge on for exactly that commit: `gh pr merge <n> --squash --auto --match-head-commit <sha>`.
- A pull request that carries a before and an after run is approved only once the after-run has completed on its head and the coordinator has read it: auto-merge does not wait for a benchmark. #5957 merged while its own after-run was failing every query, because the harness held a hand-written copy of the Sink's DDL that nothing held to the Sink. A copy of something generated (a DDL, a list of columns) that a harness or a sample keeps is held to its source by a test, split or read the way its consumer reads it (#5960).
- Never push to a pull request whose auto-merge is on. When CI fails on one, the session tells the coordinator, auto-merge goes off, the session pushes once, the new head is reviewed and auto-merge goes back on.
- A failure that is not the pull request's (a runner's network, an intermittent test of another module) gets no push: re-run the failed job once its run has completed, and file the intermittent test as an issue.
- A defect found while moving code is not fixed in the move. File it, then fix it in a pull request of its own with a test that fails on `main` (#5727, #5731 and #5743 were found this way).
- The description says what is not a move, what behaviour changes, what breaks, the sizes before and after, what was run, and the patch coverage as the gate computes it.
- A Renovate pull request is merged when green and never pushed to: a commit of ours stops Renovate from rebasing it. When an update needs a change of ours (pages a tool now prints differently, a test that asserted how a browser spells a computed value), that change is a pull request of ours from `main`, and Renovate rebases onto it. Pin a version only for a regression of the dependency, never to keep a generated page still.

### Before a push

"The local checks pass" must cover what depends on the change, not only the modules it touched. Before a push:

- Never run `./gradlew --stop`, and never kill a Gradle daemon that is not your own build's: the daemons are shared by every session on the machine, and a stop cuts whatever build another session is running (one session's stop ended another's pre-push run in mid-build). To end your own build, stop its client process.
- `./gradlew :architecture:test`: it scans every module's sources as text.
- `./gradlew :<module>:check` for every module the change touches: it runs detekt and the ABI check, which no test task does (#5783 passed every test and stopped on a wrapping rule). Detekt reads every Kotlin source set of a module (main, test, integration tests and test fixtures), reports and changes nothing, in CI and before a push alike. `-Pwow.detekt.autoCorrect` lets a local run correct the formatting rules' findings in place: it still fails on what it found, a second run passes, and what it rewrote is read before it is committed (and compiled again before compiled classes are compared).
- The unit tests of every module that depends on what changed; the whole build's `test` when a shared module changed.
- The integration tests of the modules that start a host, a kernel or a query backend on what changed (#5690 passed every unit test and stopped another module's host from starting).
- The docs checks and, for any change that moves a declaration to another file, the docs generators: a generated page that lists declarations in file order changes with a move-only pull request (#5724). A generated report that changes in any other way stops the push: show what changed and why (for an order-only change, that the sorted contents are identical) and regenerate it once the coordinator agrees (#5800). Prettier over exactly the files the change touches, the `.mjs` scripts and their tests included, as CI's job runs it (#5891 was approved and then stopped on one import line).
- The patch coverage computed as `ci-gate` computes it (`.github/scripts/patch-coverage.mjs`: 89% of changed main-source lines, a partial line counts as uncovered), from a full run of the unit suite. `node scripts/patch-coverage-local.mjs <module>…` computes it in CI's order (merge the execution data, name the sources by repository path, drop the closing braces, then judge); name every module whose tests take the changed lines. Three figures computed by hand with the steps in another order were wrong (#5810). Codecov's project figure is not that number, and a coverage file that a later single-test run overwrote is not either.
- Four kinds of line the gate scores partial are not branches of the code, and a description names the kind in a word instead of explaining it: a private companion object's synthetic member (on its `logger` line), a coroutine state machine's resume-before-invoke branch (on a `suspend fun`'s declaration), the exception copy of a `finally`, and an instruction of an inlined library function. Nothing is reshaped to cover them, and the gate's scoring stays as it is (the user's decision, 2026-11-10).
- A reachable changed line that no test takes gets a behaviour test in the same pull request, a moved line included; an unreachable one is named in the description with why (a branch the compiler generated, a state no input produces). Name the partial lines before the push, not after the gate. A new test is shown to fail when the behaviour it holds is broken (break the code locally, see it fail alone, revert).

### What a review holds a change to

- Clean architecture and code, high cohesion, low coupling, performance, extensibility, availability, usability and maintainability; progress.md §5 says what each means here. A review verifies what a description claims: it compares the lines, reads the dump, runs the name comparison, opens the benchmark report.
- No class or file grows past about 600 lines, a test file included: a new test for a type whose test file is over the limit goes in a file of its own beside it (#5815). A fix that would make one grow extracts the responsibility it adds to, in the same pull request when the extraction is small and mechanical, otherwise in a move-only pull request first. A file that is one thing stays whole and is recorded as an exception with the reason (`QueryViolation.kt`: forty-two public types nested in one sealed interface).
- A new type does not hand out its state. A mutable collection is private, and the type offers the question its callers ask, with the check and its message inside (#5738, #5761). A function does not write into a collection its caller passes when it can return or offer the value.
- Visibility says who a member is for. In a contract suite an author extends, `protected` is for what the author implements or uses, or what names a protected type; what the suite's own parents share is `internal`; no setter of the suite's state is visible to a subclass (#5746).
- A name that a parameter in scope shadows is read wrong without an error: in a local extension function the enclosing function's parameter wins over the receiver's member (#5727). Pass the value as a parameter.
- A bound on a wait is checked for what it lets through: when a close stops waiting, whatever it stopped waiting for must still be refused or finished, never answered as done. A gateway whose close gained a bound answered ACCEPTED for a command it launched on a cancelled scope until the admission was refused instead (#5829).
- A call made of parts never throws once a part of it may have been written: it returns an outcome for every part, or the caller answers what was committed as writing nothing (#5814).
- An action answers its caller only once its own state is settled. A skip that answered first and cleared its state in `finally` lost the entry taken in between (#5743).
- Before 10.0.0 GA every format number stays 1, and what a pre-release stored or sent is not considered at all: a format that must change is changed in place, with no new number, no refusal written for the older shape, no conversion and no compatibility code (the user's decision, 2026-11-10). The check that refuses a store of another format stays, since GA needs it from its first day; it has one number to know. The upgrade notes say one thing of a pre-release's stores: they are not carried over.
- A test of an absence (nothing is read, sent or handled while something holds it) keeps its power only if it still fails when the hold is gone. A change that turns such a test's sleep into a check at once, or removes a wait, shows it by mutation: remove in main, locally, what the test guards, run the test ten times, and give in the description what was removed, how many of the ten failed and at which assertion. Where a run passes, the test waits for an event a wrong action would come before, taken on the test's side, or keeps its sleep with the reason. Read alone, such a change looks right both ways: of seven checks at once approved on the argument that the test's end decides them, one had stopped failing (#5961, restored in #5967), and of seven doubted on the same reading, five were sound (#5965).
- One primary constructor with default values. A constructor overload that only supplies defaults, or keeps an older signature alive, is removed; an overload stays only where it takes a different thing (a blocking function beside a suspending one, a log entry beside its parts), and then its KDoc says what differs (the user's decision, 2026-11-10).
- A constructor takes its collaborators, and its tuning parameters (durations, sizes, switches) as one `XxxOptions` value once there are more than three: immutable, with defaults, validated in its `init`, bound from configuration in one place. A record or value type keeps its fields as parameters. No builder is added for a class that named arguments with defaults already serve; a DSL stays where it declares a nested structure (D416).

### Splitting a large file

- A split is move-only. Every removed line is compared with every added line (as whitespace-normalised multisets, with the new receiver's prefix stripped), and the description lists every line that is not a move: visibility, constructor wiring, call sites, imports.
- What moves is a responsibility with one reason to change. A type that would need most of its owner's private state opened is not an extraction: leave it, and say why. A hot loop whose decisions share one lock stays in one place.
- State moves with the lock or lane that guards it, and orderings stay visible where they are decided (ledger before memory; state settled before an answer). The description names the invariants its lines touch and the tests, run by name, that hold them.
- A class that receives moved log lines names its logger after the old owner, so that an operator's filters and alerts see no change.
- A rename is its own commit, before the move it serves.
- Tests move like sources. The sorted list of full test names is identical before and after (in Kotlin, where the class is part of the name, the method names with their count), no assertion, fixture value or timeout changes, the package's coverage is the same, and a helper that one file uses stays in that file.
- A top-level Kotlin function that moves to another file changes the class it compiles into. Before GA that is stated under `## Breaking` with the old and the new class; `@file:JvmMultifileClass` is not added to hide it.
- Where two backends hold the same responsibilities, they take the same cut and parallel names, so that a reader finds the same files in both; they share no base class when their statements and failure answers differ.
- In TypeScript, a documentation link is checked the way an editor resolves it, not the way a report tool does: a type moved out of the file that declares its target loses the link without any check failing (#5740). Lists of known exceptions are keyed by what does not move (file and target with a count), never by line number.

### Benchmarks

- Load tests and benchmarks run on GitHub Actions only (`bench.yml`), never on a developer machine. The workflow keeps one pending run per ref, whoever dispatched it: dispatch on your own branch, one run at a time, and the second of two runs meant to be read together only after the first has started.
- A pull request on a hot path (decide, append, the per-entry loop of a subscription) carries a run of the tier that passes through the code it changes; name the scenario that does.
- For the component suites (L1), allocated bytes per operation is the steadier figure and the one to read first. Between two arms of identical code it has still differed by up to about 4% on E1, 2% on E3 and 3% on E1-large (PostgreSQL's E1 by 4.1% and E1-large by 2.7% in one same-commit pair, #5852), so one row past a bound decides nothing: a cost the change added shows in every row, in the same direction. Store round trips per operation of identical code differ by up to 5% between two arms of one run and more between runs. A component run at 1k is a fixed 20,000 operations, so an arm's operation count explains nothing. The bounds are for a cost that went up: a row that went down needs no repeat and no pair.
- A flagged round-trip difference is read by command before anything else. When it lies only in commands the change does not issue (inserts and updates for a pull request that moved reads), it is the run, not the change. When neither that reading nor the compiled classes settle it, the run is repeated once and set against a pair with the same commit as both arms; it is a finding only when it repeats beyond that pair's spread.
- For a pure move, compare the compiled classes before and after (`javap -c -p` at the merge base and at the head): the same allocating instructions in each moved function and its callers, the same fields in each continuation and lambda class. It takes minutes, is not a load test, and answers directly what a benchmark only samples.
- A micro-benchmark's absolute figure is the runner's: two runs of one program differed by 1.5 to 1.9 times between two CPU models (D412's insert experiment). Compare ratios within a run, and name the CPU beside any absolute figure a decision rests on.
- The shared benchmark server gains no backend for the sake of one suite: every suite's arms run that distribution, and its start-up and memory are what the comparison with other frameworks reads. A suite that needs another store gets a server of its own.
- Do not explain a difference with a pattern drawn from a handful of arms. "Round trips follow throughput" held in three runs and was contradicted by the fourth.

### Failures in CI

- A test that fails once is a defect until its cause is known, in whatever run it failed and however unrelated the pull request: it is filed that day, not noted as "unrelated, then green". Find the interleaving by reading the code against the test; do not re-run until it passes, and do not raise a timeout. A slow runner is a candidate to rule out, not an explanation to assume: the ten-second timeout of #5743 was an entry the runtime had lost, which no timeout would have passed.
- The regression test holds the interleaving with a gate, not a sleep, and fails on `main` at once. A test that proves an order does not compare a duration either: it is built so that the wrong order cannot finish (#5831).
- When a gate stops a pull request that was approved, the review missed it too: add what would have caught it to the list above, not only to the pull request.

## Documentation

Each kind of document has one home, chosen by its reader; [Documentation Governance](documentation/docs/en/contribute/documentation.md) is the full policy. `pnpm check:docs-layout` checks the tracked files against it; the `docs-layout` job of `ci.yml` runs it on every pull request.

- User documentation: `documentation/docs/{en,zh}/` (VitePress). Update the owning page in the same change as the behavior, and keep the English and Chinese pages structurally aligned.
- Entry and module usage: `README.md` and `README.zh-CN.md`, at the root and beside each published module or package. They link to the site instead of copying it.
- Design records: `documentation/designs/YYYY-MM-DD-<topic>-design.md` for decisions spanning JVM modules; `<package>/docs/design/` for one TypeScript package or application. State the date, source baseline and status, and update the status when the work lands.
- Ledgers of decided work: the JVM's in `documentation/designs/roadmap.md` §6, `typescript/TODO.md`, `typescript/wow-view-engine/docs/design/todo.md`, `examples/eshop/DX-GAPS.md`. Delete an entry when it is done; open review findings go here, not into a committed review.
- Performance evidence: `test/benchmarks/results/`, only with the commit, command, environment and raw results that reproduce it.
- Static assets: `documentation/docs/public/`. Diagram sources Mermaid cannot express: `documentation/diagrams/`.
- Prefer Mermaid source (`.mmd` or fenced `mermaid`) for every diagram Mermaid supports; do not commit a generated SVG beside it. Use PlantUML only for unsupported diagram kinds such as use case diagrams.
- Do not commit implementation plans, review notes, QA comparisons or scratch evidence; keep them in the pull request or an ignored workspace. Do not add files under `document/` or `docs/superpowers/`.
- Project-local skills: `skills/`; vendored Claude Code skills (shadcn): `.claude/skills/`
