# Wow - Agent Instructions

## Build & Run

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew <module>:clean <module>:check --stacktrace
./gradlew detekt
./gradlew detekt --auto-correct
./gradlew build
./gradlew checkKotlinAbi
./gradlew updateKotlinAbi
```

Use Gradle module paths from `settings.gradle.kts`, for example `:wow-api`, `:wow-core`, `:wow-spring-boot-starter`, `:wow-compensation-domain`, `:example-domain`, and `:wow-test`.

Before running a sample, prepare its module directory from the repository root. Set `service_dir` to `example/example-server`, `example/transfer/example-transfer-server`, or `compensation/wow-compensation-server`:

```bash
service_dir=example/example-server
mkdir -p "$service_dir/logs" "$service_dir/data" "$service_dir/config"
test -e "$service_dir/config/application.yaml" || cp "$service_dir/src/dist/config/application.yaml" "$service_dir/config/application.yaml"
```

The `run` task resolves `logs/`, `data/`, and `config/` relative to that module directory. Review the copied configuration and start any backends it enables. The example and transfer distribution configs use in-memory Wow stores and buses; the compensation config needs MongoDB, Redis/CosId, and Kafka connection settings appropriate to your environment. Keep local configuration, logs, and heap dumps out of commits.

Choose the matching service command:

```bash
./gradlew :example-server:run
./gradlew :example-transfer-server:run
./gradlew :wow-compensation-server:run
```

JavaScript projects share one pnpm workspace rooted at the repository root (`package.json`, `pnpm-workspace.yaml`, one `pnpm-lock.yaml`). Install once from the root; TypeScript packages under `typescript/` follow [typescript/AGENTS.md](typescript/AGENTS.md).

```bash
pnpm install
```

Compensation dashboard:

The dashboard uses the workspace packages `@ahoo-wang/wow-client`, `@ahoo-wang/wow-react`, `@ahoo-wang/wow-view-engine` and `@ahoo-wang/wow-view-store` through their `dist`, so build them before running it and again after changing them:

```bash
pnpm --filter wow-compensation-dashboard^... build
cd compensation/dashboard
pnpm dev
pnpm test
pnpm lint
pnpm build
pnpm coverage
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
| `src/test` | `<module>:test` | `allLocalTest` | No containers |
| `src/contractTest` | `<module>:contractTest` | `allContractTest` | No containers; shared TCK contracts |
| `src/integrationTest` | `<module>:integrationTest` | `allIntegrationTest` | Docker/Testcontainers |

`check` includes `test` and registered `contractTest` tasks, but does not run `integrationTest`. For storage or broker changes, run the affected module's integration tests explicitly. Use only layers registered for that module in `build.gradle.kts`; see [test runtime guidance](documentation/docs/zh/guide/test-runtime.md) for module coverage and setup.

```bash
./gradlew :wow-core:test
./gradlew :wow-core:test --tests "me.ahoo.wow.command.DefaultCommandGatewayTest"
./gradlew :wow-core:contractTest
./gradlew :example-domain:test --tests "me.ahoo.wow.example.domain.order.OrderSpec"
./gradlew :wow-compensation-domain:check
./gradlew :wow-mongo:integrationTest --stacktrace
./gradlew :wow-it:integrationTest --stacktrace
```

Domain tests usually use the Wow test DSL:

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        whenCommand(AddCartItem(productId = "productId", quantity = 1)) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState {
                items.assert().hasSize(1)
            }
        }
    }
})
```

CI sets `CI=GITHUB_ACTIONS`; Gradle test retry is enabled only in CI with up to 2 retries and 20 max failures. Domain modules enforce Jacoco coverage, commonly 80% minimum.

## Project Structure

```text
wow-api/                    Pure API contracts: commands, events, naming, queries, modeling
wow-metadata/               wow-metadata.json model and naming strategies, shared by wow-core and wow-compiler
wow-core/                   Core CQRS, event sourcing, messaging, projection, saga runtime
wow-compiler/               KSP processors for metadata and query helper code
wow-spring/                 Spring integration primitives
wow-spring-boot-starter/    Auto-configuration plus optional feature capabilities
wow-query/                  Query model support
wow-kafka/                  Kafka command/event bus integration
wow-mongo/                  MongoDB event store and snapshot store
wow-redis/                  Redis event store and snapshot store
wow-elasticsearch/          Elasticsearch projection support
wow-webflux/                Spring WebFlux command endpoint integration
wow-opentelemetry/          Tracing and metrics integration
wow-cosec/                  CoSec authorization integration
wow-cocache/                CoCache projection caching
wow-apiclient/              REST API client using CoApi
wow-rest-contract/          REST wire vocabulary: header names, route paths, BatchResult, BI script DTOs
wow-openapi/                Route contracts and runtime OpenAPI generation
wow-schema/                 JSON Schema generation
wow-bi/                     BI sync script generation
wow-models/                 Shared model helpers
wow-bom/, wow-dependencies/ BOM and centralized dependency versions
test/                       wow-test DSL, TCK, mocks, integration tests, coverage report
compensation/               Compensation domain, API, core, server, and React dashboard
view-store/                 View engine storage backend: API, domain, Spring Boot starter, standalone server
typescript/                 TypeScript client packages moving in from fetcher; see typescript/AGENTS.md
example/                    Kotlin order/cart sample and Java transfer sample
documentation/              VitePress documentation site
document/                   Design docs, diagrams, and static assets
skills/                     Project-local Codex skills and agent definitions
```

Core dependency flow:

```text
wow-api -> wow-metadata -> wow-core -> wow-spring -> wow-spring-boot-starter
                                    -> infrastructure modules: kafka, mongo, redis, elasticsearch
                                    -> integration modules: webflux, opentelemetry, cosec, cocache, apiclient
                        -> wow-compiler (KSP; no wow-core dependency)
```

`wow-spring-boot-starter` declares Gradle feature variants for `mongo-support`, `redis-support`, `mock-support`, `kafka-support`, `webflux-support`, `elasticsearch-support`, `opentelemetry-support`, `openapi-support`, `cosec-support`, and `bi-support` (`wow-bi`; the BI script route exists only when `wow-bi` is on the classpath).


## Code Style

- Kotlin and KSP versions come from `gradle/libs.versions.toml`; JVM toolchain 17 is configured in `build.gradle.kts`, and `kotlin.code.style=official` in `gradle.properties`.
- Spring Boot dependency management is centralized through `wow-dependencies`, which imports the BOM version from `gradle/libs.versions.toml`.
- All JVM packages live under `me.ahoo.wow`; examples use `me.ahoo.wow.example`.
- Source files use the Apache 2.0 copyright header already present in the repository.
- Keep command/event paths reactive with Reactor `Mono`/`Flux`; do not introduce blocking calls into core dispatch, event store, projection, saga, or transport flows.
- Prefer focused interfaces and module boundaries: API contracts in `wow-api`, runtime behavior in `wow-core`, Spring wiring in `wow-spring*`, and storage or transport concerns in their dedicated modules.
- Serialization uses Jackson; framework code uses the existing Jackson stack and Spring compatibility modules where already present.
- ID generation uses CosId and existing Wow ID helpers.
- Logging uses kotlin-logging, SLF4J, and Logback.
- Detekt uses `config/detekt/detekt.yml`; line-length and several style rules are intentionally relaxed.

Typical Kotlin API style:

```kotlin
interface CommandBus :
    MessageBus<CommandMessage<*>, ServerCommandExchange<*>>,
    TopicKindCapable {
    override val topicKind: TopicKind
        get() = TopicKind.COMMAND
}
```

Dashboard code uses React, TypeScript, Vite, shadcn/Base UI, Tailwind CSS, React Router, Vitest, and ESLint. Reuse components under `compensation/dashboard/src/components/ui/`; dependency versions live in the `catalog` of the root `pnpm-workspace.yaml`; use `compensation/dashboard/package.json` for the dependency list and `compensation/dashboard/components.json` for shadcn configuration. Wow clients are generated under `compensation/dashboard/src/generated/` by `wow-generator` (`@ahoo-wang/wow-generator`) from the compensation server's `/v3/api-docs`; commit the generator output byte for byte, including `.wow-generator.json`. Do not hand-edit generated client files unless the generator input is unavailable and the user accepts that tradeoff.

## Version Management

The project version is the `version` property in `gradle.properties`. Maven and npm release together under that version and one `v<version>` tag. Third-party versions are centralized in `gradle/libs.versions.toml` and the `wow-dependencies` module.

A release is prepared in one pull request (`chore(release): prepare <version>`):

1. `pnpm set-version <version>` writes `gradle.properties` and the version of every `typescript/*/package.json`, `compensation/dashboard/package.json` and `documentation/package.json`. It then lists the tracked files that still mention the old version.
2. Update the ones that track the release by hand: the version tables in `README.md` and `README.zh-CN.md`, `documentation/docs/{en,zh}/guide/existing-project.md` and `getting-started.md`, and `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`. A pre-release (`x.y.z-rc.n`) is published to npm only, so its PR updates the snapshot but leaves the README tables and those pages on the last stable version; they move with the stable release.
3. `pnpm check:versions` confirms every `package.json` matches; the `quality` job of `typescript.yml` runs the same check.

Breaking changes, Kotlin or TypeScript, ship only in an `x.Y.0` release. A change breaks when it changes public API (Kotlin, or a published npm package), REST behaviour, configuration, storage or wire formats; mark it with `!` in the conventional commit, or tick the PR template's **Breaking** box (it labels the PR `breaking-change`), and give the description a `## Breaking` section saying who is affected and how to migrate (for an approved v9 exception, also the step before upgrading and what a mixed 9.x cluster does). A `## Behaviour changes` section is for user-visible changes that are not breaking; it adds no label. Release admission enforces this, and requires the x.Y.0 release notes to name each such PR. Source-level compatibility for application code (annotations, DSLs and functions apps write) is listed in `docs/compat-debt.md` and kept until v10; `pnpm check:compat-debt` checks its markers.

### Binary Compatibility

Every published Maven module except the BOMs (`publishProjects` minus `bomProjects` in `build.gradle.kts`) keeps its public JVM ABI in `<module>/api/<module>.api`: classes, constructors, methods and fields with their JVM signatures, including synthetic overloads such as a `DeprecationLevel.HIDDEN` constructor kept for old callers. Declarations marked `@InternalWowApi` are left out; `@WowSpi` declarations (the query backend and command aggregate SPI, an opt-in with a compiler warning) stay in the dump. An interface member marked `@InternalWowApi` also drops out of the dumps of the classes in other modules that implement the interface. The dumps come from Kotlin's built-in ABI validation (`abiValidation` in the Kotlin Gradle plugin); `check` depends on `checkKotlinAbi`, and the `local-test.yml` workflow runs `./gradlew checkKotlinAbi` before the tests, so a pull request that changes a public signature without its dump fails.

When the check fails, run `./gradlew updateKotlinAbi` (or `./gradlew :<module>:updateKotlinAbi`) and commit the rewritten dumps with the change. Read the dump diff before committing it:

- Only `+` lines (a new class, member or overload): additive, allowed in any 9.x release, patches included.
- Any `-` line, or a changed one (a removed or renamed declaration, a parameter added to a constructor or function even with a default value, a changed return type): a binary break. In a patch, keep the old signature instead, for example a secondary constructor or overload marked `@Deprecated(…, level = DeprecationLevel.HIDDEN)` with a `compat(<scope>)` comment and an entry in `docs/compat-debt.md`, so the dump goes back to additive. A removal ships only in an `x.Y.0`, marked breaking as above, with a `## Breaking` section naming the removed signatures and what callers use instead. An `x.Y.0` keeps no binary-only shims: it changes signatures cleanly instead of adding hidden overloads or leftover non-bean copies, and drops the ones earlier patches added. Application-facing source API (annotations, DSLs, functions apps call) still gets one `@Deprecated` cycle, listed in `docs/compat-debt.md`.

A module that starts publishing commits its first dump in the same pull request; the check fails while a published module has none.

`pr-labeler.yml` applies the same rule to the diff: a pull request that removes or changes a line of a Kotlin dump (`**/api/*.api`), a TypeScript API report (`typescript/*/test/api/*.api.md`) or a surface list (`typescript/*/test/surface/*.txt`) is labelled `breaking-change`, which keeps it out of a patch release; `+` lines alone add no label. Blank lines, a surface list's `#` header, an API report's `//` comments and lines that only moved or were re-indented within the same file do not count (`.github/scripts/breaking-label.mjs`). When a changed line is not a break, a maintainer removes the label after the last push (the labeler runs again on every push and adds it back) and says why in the pull request.

## CI And Release Workflows

GitHub Actions run module-level checks from `.github/workflows/`:

- `local-test.yml` checks every published module's Kotlin ABI against its `api/*.api` dump (`checkKotlinAbi`, see Binary Compatibility), then runs `allLocalTest` and local coverage.
- `contract-test.yml` runs `allContractTest` and contract coverage.
- `integration-test.yml` runs `allIntegrationTest` and integration coverage.
- `mixed-version.yml` runs `MixedVersionClusterTest` (`:wow-it`): the released example server image (`PREVIOUS_IMAGE`) and the example server built from the pull request share Kafka and MongoDB and must process each other's commands, events and wait signals. Its scope (`mixedVersion` in `.github/scripts/ci-scope.mjs`) covers the wire modules, the example server, `gradle/` and the harness; `mixed-version-gate` is its merge signal. Move `PREVIOUS_IMAGE` to the newest release once its image is published.
- `compensation-test.yml` checks compensation core and domain modules.
- `example-java-test.yml` builds the Java transfer example modules.
- `codecov.yml` publishes coverage. Codecov (`codecov.yml` at the root) reports pull requests from the `local`, `contract` and `integration` flags: changed main code needs 90% patch coverage, and each published Kotlin module (and TypeScript package, from its own flag) is a component whose coverage must not drop by more than 1%; a module's coverage counts every layer, so storage adapters' I/O is covered by their container tests.
- `documentation-deploy.yml`, `example-deploy.yml`, `compensation-deploy.yml`, and `view-store-deploy.yml` deploy the docs and push the Docker images of the example, compensation and standalone view store servers (DockerHub `ahoowang/`, GHCR `ghcr.io/ahoo-wang/`, Aliyun `registry.cn-shanghai.aliyuncs.com/ahoo/`). Each image workflow runs daily, on a push to `main` or a `v*.*.*` tag that touches its `paths` (the sources its server is built from), and on `workflow_dispatch`; keep those `paths` in step with the server's project dependencies. A release tag moves `latest` only when it is the highest stable `v*` tag (`.github/scripts/docker-latest.mjs`, the rule npm's dist-tag follows), so a patch to an older line keeps `latest` where it is.
- `typescript.yml` runs on every pull request; its scope job decides which TypeScript jobs run, and `typescript-gate` is the merge signal for JavaScript changes. `dashboard-test.yml` checks the compensation dashboard.
- `typescript-contract.yml` runs the TypeScript client, generator and integration tests against an example server built from the same commit (the view engine and view store suites a second time with its events and snapshots on Elasticsearch, `contract-elasticsearch`), and type-checks code generated from the 8.10.8 and 8.11.5 server images; the shared scope script decides which part runs, and `typescript-contract-gate` is its merge signal.
- `typescript-storybook.yml` runs when the stories, view-engine, wow-client or wow-react change: it type-checks, lints and builds `typescript/storybook` (verifying its index), then runs the story interactions in Chromium in four shards balanced by measured file durations (`typescript/storybook/test-durations.json`); `typescript-storybook-gate` is its merge signal.
- `pr-labeler.yml` labels pull requests by branch and changed paths (`.github/labeler.yml`), and adds `breaking-change` to a title of the form `type(scope)!: …` or a description with a `BREAKING CHANGE:` line, the template's **Breaking** box ticked, or a `## Breaking` / `## Breaking changes` section that is not "None.", and to a diff that removes or changes a line of an ABI dump, API report or surface list (see Binary Compatibility; `.github/scripts/breaking-label.mjs`); `.github/release.yml` lists breaking changes first in generated release notes.
- `typescript-storybook-browsers.yml` runs the same story interactions in Firefox (two shards) and WebKit (four shards, on macOS), nightly and on `workflow_dispatch`. It never runs on a pull request, is not a merge signal, and release admission does not read it.
- `package-deploy.yml` publishes when a GitHub Release is created, or when it is dispatched by hand on a `v*` tag; any other ref is refused. Every action in it is pinned to a commit SHA. `admission` checks that the tag, `gradle.properties` and every `package.json` agree and runs release admission (`.github/scripts/release-admission.mjs`). `preflight` builds, packs and checks the npm tarballs (`.github/scripts/package-check.mjs`), dry-runs the publish, and runs the Gradle build and integration tests. Then `github-deploy` (GitHub Packages) and `central-deploy` (Maven Central) run in parallel; once both succeed, `npm-deploy` publishes in the `npm-publish` environment (`v*` tags only, no reviewer; npm's trusted publisher is bound to it). After it, `npm-smoke` (`package-check.mjs --registry`, on Node 22.12.0 and 24) waits a bounded time for npm to serve the version, checks the dist-tags, and installs, imports, requires and type-checks the published packages in a clean project.
- Release admission requires the release commit to be on `main` or a `release-x.y` branch, and a successful `workflow_dispatch` run of each of `typescript.yml`, `typescript-contract.yml` and `typescript-storybook.yml` on that commit with its gate job passing (a dispatch run turns every scope on; push runs do not count). It dispatches the runs a commit lacks and waits for them. It refuses a patch release when any commit since the previous `v*` tag is breaking (`!`, a `BREAKING CHANGE:` footer, or a pull request labelled `breaking-change`), and refuses an `x.Y.0` whose GitHub release notes do not name every such pull request (`#number`) on the release branch.
- `npm-deploy` publishes the tarballs `preflight` packed, through `.github/scripts/publish-npm.mjs --tarballs`, without installing or building. It publishes every package in `PUBLISHED` (from 9.2.0: `@ahoo-wang/wow-client`, `@ahoo-wang/wow-react`, `@ahoo-wang/wow-generator`, `@ahoo-wang/wow-view-engine` and `@ahoo-wang/wow-view-store`) with OIDC trusted publishing and provenance, skips a version already on npm (so a failed run can be re-run), and tags a patch to an older line `release-<major>.<minor>` instead of `latest`. A real publish refuses a dirty working tree or a HEAD that is not the commit of `v<version>`. Private packages are never published. A new public package must be added to `PUBLISHED` or `HELD_BACK` in that script. Maven modules that are not ready to publish go in `incubatingProjects` in `build.gradle.kts` (empty from 9.2.0, which publishes `view-store/` api, domain and starter); `wow-bom` constrains only the published modules. The maintainer runbook, including the first npm release, is `typescript/RELEASING.md`.

Before changing release or publish behavior, inspect the workflow and Gradle publishing configuration together.

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
- Never edit generated dashboard clients in `compensation/dashboard/src/generated/` as the primary fix when the OpenAPI source or generator can be fixed instead.
- Never bypass Reactor with blocking code in core runtime paths.

## Documentation

- Root README: `README.md`
- Chinese README: `README.zh-CN.md`
- VitePress docs: `documentation/docs/`
- Static documentation assets: `documentation/docs/public/`
- Diagram sources Mermaid cannot express: `documentation/diagrams/`
- `document/` and tracked `docs/superpowers/` are legacy migration sources; do not add new files there.
- Prefer Mermaid source (`.mmd` or fenced `mermaid`) for every diagram Mermaid supports; do not commit a generated SVG beside it. Use PlantUML only for unsupported diagram kinds such as use case diagrams.
- Project-local skills: `skills/`; vendored Claude Code skills (shadcn): `.claude/skills/`
