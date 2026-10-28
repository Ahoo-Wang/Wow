# Wow - Agent Instructions

## Build & Run

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew <module>:clean <module>:check --stacktrace
./gradlew :check
./gradlew :integrationTest
./gradlew detekt
./gradlew detekt --auto-correct
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
compiler/gradle-plugin/     :gradle-plugin  Gradle plugin `me.ahoo.wow`: applies KSP and the compiler, assembles the Manifest
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
test/benchmarks/            :benchmark-server, :benchmark-load: the end-to-end benchmark (D224; harness/, bench.yml) and the performance reports (results/)
examples/eshop/<context>/    :eshop-<context>-{api,domain,server}: the example, the eShop's five contexts (D159, D204); never published
examples/eshop/e2e/          :eshop-e2e: the eShop's services end to end on PostgreSQL, Kafka and ClickHouse
```

- Gradle project names and directories carry no `wow-` prefix (`:kernel` in `core/kernel`); the root build sets their jar name and Maven artifactId to `wow-<name>` (D87). `:architecture` forbids including or depending on a deleted 9.x project (`DELETED_9X`).
- A release publishes the modules (R.1; see Releasing): `abiProjects` plus `:migrate-data` and `:manifest-tools`, the BOM `:bom` (`bom/bom`, artifact `wow-bom`, constraining exactly those) and `:wow-dependencies` (`bom/dependencies`), which every POM but the contract modules' imports; the platform services, `:architecture`, the spikes and the eShop stay unpublished (`incubatingProjects`). Their ABI is frozen at the M7 exit (D88, D147, D216): `abiProjects` in `build.gradle.kts` (every published Wow module except `:migrate-data` and `:manifest-tools`) joins the `abiValidation` block, keeps its dump in `<module>/api/<module>.api` (recorded by D216 F.5), and `:check` runs its `checkKotlinAbi`; `./gradlew updateKotlinAbi` rewrites the dumps. Between the M7 exit and 10.0.0 GA a dump is a review tool, see Binary Compatibility.
- API levels (D216): a declaration only its own module uses is `internal`; one Wow's modules share but promise nothing about is `@InternalWowApi` (an `ERROR`-level opt-in, left out of the dumps; mark a constructor to hide only how a public class is built); a surface 10.x may still change (dataset queries, the HTTP ops routes and webhooks, ABAC query policies) is `@ExperimentalWowApi` (a `WARNING`-level opt-in, kept in the dumps). Only Wow's own modules opt in to both; the eShop, the platform services' domains and servers and `:architecture` (`userProjects`) do not, so they compile against the public API as a user does: opt in with `@OptIn(ExperimentalWowApi::class)` where they use an experimental surface, and never opt in to `InternalWowApi`. Wow modules compile with `-jvm-default=no-compatibility` (no `DefaultImpls` classes).
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
- ID generation uses CosId and existing Wow ID helpers.
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

1. `pnpm set-version <version>` writes `gradle.properties` and the version of every `typescript/*/package.json`, `platform/ops/console/package.json` and `documentation/package.json`. It then lists the tracked files that still mention the old version.
2. Update the ones that track the release by hand: the services' `manifest/baseline.json` (`./gradlew updateManifestBaseline`), and `documentation/docs/{en,zh}/start/quickstart.md`, `existing-project.md` and any other docs page that pins the version. The docs are the Wow 10 docs, so they move with every 10.x release, pre-releases included; the 9.x docs live on `v9`. The README shows no Wow version, the OpenAPI snapshots (`edge/openapi/src/test/resources/openapi/`) record none, and the design records under `documentation/designs/` keep the versions they name.
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

- `ci.yml` is the one CI workflow, on every pull request to and push to `main`. Its scope job (`.github/scripts/ci-scope.mjs`) decides from the changed paths which areas run: the JVM jobs (`jvm`: the modules, their tests, the examples, `bom/`, the root build and Gradle configuration) run `:check` in four shards (`-Pwow.check.shard`: `host`, `apps`, `tooling` and `rest`, which takes every module the others do not name, from `checkShards` in `build.gradle.kts`; `rest` also runs `publicationCheck`) and `:integrationTest` in four shards, both balanced by measured durations (`-Pwow.integration.projects`; `.github/scripts/integration-shards.mjs` checks every integration test project is in exactly one shard and the check matrix runs each check shard once), with the Gradle cache written only by pushes to `main`; the TypeScript jobs (static checks, the unit matrix, the package checks, the fetcher floor, view-engine's shards, the site), the Storybook jobs (build, interactions in Chromium in four shards balanced by `typescript/storybook/test-durations.json`, the screenshot baselines), the view store contract (the view store suites against the service built from the commit), the eShop web app, the ops console and the analytics catalog console (each with its end-to-end suite), the docs layout and repository hygiene (`pnpm check:hygiene`, `.github/scripts/repo-hygiene.mjs`: no version catalog entry that no build script uses, no `.gitignore`, `.prettierignore` or labeler path that matches nothing, no CI script that nothing runs; a known leftover goes in its `ALLOWED` with the change that removes it) and actionlint over every workflow. A docs-only or TypeScript-only change skips the JVM jobs. `ci-gate` is the merge signal: it passes when every job that ran passed, and, on a pull request whose run uploaded coverage (the JVM check or shards, the TypeScript units or view-engine), when Codecov's `codecov/patch` on the head commit passes (`.github/scripts/codecov-patch.mjs`, waiting up to 15 minutes; the newest run Codecov started after the run's last coverage upload step decides, and without one an earlier run counts only if it passed, since carried-forward sessions satisfy `after_n_builds` and Codecov may post an early failure on the base's JVM coverage but does not post an unchanged result again); a run without coverage leaves that out, so patch coverage is required without blocking changes Codecov never reports on. `main`'s branch protection requires `ci-gate` and PR Safety. A manual dispatch runs everything (`-f update-screenshots=true` takes new Storybook baselines). The Node 22 jobs (the unit matrix's Node 22 legs, view-engine's Node 22 shards and `Package / Node 22.12.0`) run on pushes to `main` and on dispatch, not on pull requests, to spare runners. `nightly.yml` (a schedule runs from the default branch, `main`) dispatches the eShop's soak (`soak.yml`) and a full `ci.yml` run on `main` every night that `main` moved. The 9.x Gradle workflows left `v10` with the 9.x code (D262) and run on main.
- Codecov (`codecov.yml` at the root) reports pull requests from the `jvm-unit` flag (the check's report), the `integration` flag (one upload per shard) and the TypeScript packages' flags: changed main code needs 90% patch coverage, and each module (a component named after its Gradle project) and TypeScript package is a component whose coverage must not drop by more than 1%; a module's coverage counts its unit and container tests, so storage adapters' I/O is covered by their container tests. Statuses wait for each flag's `after_n_builds`, which carried-forward sessions satisfy until the flag's first upload, so a status may post early and again after the JVM uploads (`ci-gate` waits for the later one unless the early one passed); no flag has `paths`, which would widen every component to the whole flag.
- `documentation-deploy.yml` deploys the docs, by hand only until the documentation (W1/W2) is done; DI-6 (#4699) turns its push and schedule back on. `image-deploy.yml`, called by `package-deploy.yml`, pushes the server images (DockerHub `ahoowang/`, GHCR `ghcr.io/ahoo-wang/`, Aliyun `registry.cn-shanghai.aliyuncs.com/ahoo/`). The 9.x image workflows (example, compensation, view store, analytics) left with the 9.x code (D262) and run on `v9`.
- `pr-labeler.yml` labels pull requests by branch and changed paths (`.github/labeler.yml`), and adds `breaking-change` to a title of the form `type(scope)!: …` or a description with a `BREAKING CHANGE:` line, the template's **Breaking** box ticked, or a `## Breaking` / `## Breaking changes` section that is not "None.", and to a diff that removes or changes a line of an ABI dump, API report or surface list (see Binary Compatibility; `.github/scripts/breaking-label.mjs`); `.github/release.yml` lists breaking changes first in generated release notes.
- `typescript-storybook-browsers.yml` runs the same story interactions in Firefox (two shards) and WebKit (four shards, on macOS), nightly and on `workflow_dispatch`. It never runs on a pull request, is not a merge signal, and release admission does not read it.
- `package-deploy.yml` publishes when a GitHub Release is created, or when it is dispatched by hand on a `v*` tag; any other ref is refused. Every action in it is pinned to a commit SHA. `admission` checks that the tag, `gradle.properties` and every `package.json` agree and runs release admission (`.github/scripts/release-admission.mjs`). `preflight` builds, packs and checks the npm tarballs (`.github/scripts/package-check.mjs`), dry-runs the publish, and runs the Gradle build and integration tests. Then `github-deploy` (GitHub Packages) and `central-deploy` (Maven Central) run in parallel; once both succeed, `npm-deploy` publishes in the `npm-publish` environment (`v*` tags only, no reviewer; npm's trusted publisher is bound to it). After it, `npm-smoke` (`package-check.mjs --registry`, on Node 22.12.0 and 24) waits a bounded time for npm to serve the version, checks the dist-tags, and installs, imports, requires and type-checks the published packages in a clean project.
- Release admission requires the release commit to be on `main` or a `release-x.y` branch (9.x releases from `v9`, with its own copy of the script), and a successful `workflow_dispatch` run of `ci.yml` on that commit with `ci-gate` passing (a dispatch run turns every scope on; push runs do not count). It dispatches the run a commit lacks and waits for them. It refuses a patch release when any commit since the previous `v*` tag is breaking (`!`, a `BREAKING CHANGE:` footer, or a pull request labelled `breaking-change`), and refuses an `x.Y.0` whose GitHub release notes do not name every such pull request (`#number`) on the release branch.
- `npm-deploy` publishes the tarballs `preflight` packed, through `.github/scripts/publish-npm.mjs --tarballs`, without installing or building. It publishes every package in `PUBLISHED` (from 9.2.0: `@ahoo-wang/wow-client`, `@ahoo-wang/wow-react`, `@ahoo-wang/wow-generator`, `@ahoo-wang/wow-view-engine` and `@ahoo-wang/wow-view-store`) with OIDC trusted publishing and provenance, skips a version already on npm (so a failed run can be re-run), and tags a patch to an older line `release-<major>.<minor>` instead of `latest`. A real publish refuses a dirty working tree or a HEAD that is not the commit of `v<version>`. Private packages are never published. A new public package must be added to `PUBLISHED` or `HELD_BACK` in that script. Maven modules that are not ready to publish go in `incubatingProjects` in `build.gradle.kts` (empty today: the dataset registry left it with the analytics service); `wow-bom` constrains only the published modules. The maintainer runbook, including the first npm release, is `typescript/RELEASING.md`.

Before changing release or publish behavior, inspect the workflow and Gradle publishing configuration together.

### Releasing

On `main`, `package-deploy.yml` releases the 10.x line, stable (`v10.x.y`) and pre-release (`v10.x.y-alpha.n`, `-beta.n`, `-rc.n`; D202, D217, D265); a tag of an older major is refused, since 9.x releases from `v9`. Its gates are `:check :integrationTest` and `publicationCheck -Pwow.publication.signed=true`, and the Maven jobs publish the modules (the build has no 9.x module). `.github/scripts/release-plan.mjs` decides the rest from the tag and every `v*` tag, for both `package-deploy.yml` and `image-deploy.yml`, which pushes `wow-ops-server`, `wow-view-store-server` and `wow-analytics-catalog-server`:

- A pre-release: npm publishes under `alpha`, `beta` or `next` and `publish-npm.mjs` refuses `latest` for any pre-release; the images get the version alone; the GitHub release is a pre-release, never latest, with `.github/release-notes/prerelease.md` (no compatibility promise; 9.x data migration is a rehearsed pre-release tool, not for production data; not for production). A pre-release carries no compatibility promise.
- A stable release on the latest line (the highest stable `v*` tag): npm publishes under `latest`; the images get the version, `X.Y` and `latest`; the GitHub release is not a pre-release and is marked latest, with `.github/release-notes/stable.md` when none exists. An `x.Y.0` with breaking changes needs its GitHub release, naming each of them, before admission.
- A stable patch to an older line: npm publishes under `release-X.Y`; the images get the version and `X.Y`; the GitHub release is not marked latest.

Steps:

1. Rehearse: `./gradlew :check` and `./gradlew -Pversion=<version> publicationCheck` (signs, with a throwaway GnuPG key unless `-Pwow.publication.signed=true` gives it the release key, so it needs `gpg`; publishes into `build/publication-check/repo` and checks every jar, sources and javadoc jar, POM, module metadata and their signatures, the BOM and that nothing else is published, no extra classifier such as a test-fixtures jar either; then runs the deploy jobs' publish as they do, `-Pwow.publication.prebuilt=true` with CI set and no `SIGNING_*` key, which uploads the preflight's files and signatures without rebuilding or re-signing them, into `build/publication-check/deploy`, and requires that it runs nothing but publish tasks, uploads the same files byte for byte and that every signature verifies; `ci.yml` runs it with the throwaway key on every pull request), `pnpm test:ci-scripts`, `node .github/scripts/package-check.mjs`.
2. Prepare the version in one pull request to `main` (`chore(release): prepare <version>`): `pnpm set-version <version>` (gradle.properties and every versioned `package.json`; `v9` keeps its 9.x version), then refresh what records the version, such as the services' `manifest/baseline.json`. Move the docs pages that pin the version to it too (Version Management, step 2).
3. Dispatch the full `ci.yml` run release admission needs on the merged commit, or let admission dispatch it.
4. **The user confirms first** (D124, D202): push the tag `v<version>` on the merged `main` commit.
5. Dispatch `package-deploy.yml` on that tag ("Use workflow from" → the tag), or create its GitHub release by hand. It admits, gates, publishes Maven, then npm, smoke-tests npm, pushes the images and creates or marks the GitHub release as the plan says; edit the "What is in it" section of a release it created afterwards.

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
