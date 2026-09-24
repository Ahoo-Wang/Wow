# Wow - Agent Instructions

## Build & Run

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew <module>:clean <module>:check --stacktrace
./gradlew detekt
./gradlew detekt --auto-correct
./gradlew build
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

```bash
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
wow-openapi/                Route contracts and runtime OpenAPI generation
wow-schema/                 JSON Schema generation
wow-bi/                     BI sync script generation
wow-models/                 Shared model helpers
wow-bom/, wow-dependencies/ BOM and centralized dependency versions
test/                       wow-test DSL, TCK, mocks, integration tests, coverage report
compensation/               Compensation domain, API, core, server, and React dashboard
typescript/                 TypeScript client packages moving in from fetcher; see typescript/AGENTS.md
example/                    Kotlin order/cart sample and Java transfer sample
documentation/              VitePress documentation site
document/                   Design docs, diagrams, and static assets
skills/                     Project-local Codex skills and agent definitions
```

Core dependency flow:

```text
wow-api -> wow-core -> wow-spring -> wow-spring-boot-starter
                    -> infrastructure modules: kafka, mongo, redis, elasticsearch
                    -> integration modules: webflux, opentelemetry, cosec, cocache, apiclient
```

`wow-spring-boot-starter` declares Gradle feature variants for `mongo-support`, `redis-support`, `mock-support`, `kafka-support`, `webflux-support`, `elasticsearch-support`, `opentelemetry-support`, `openapi-support`, and `cosec-support`.


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

Dashboard code uses React, TypeScript, Vite, shadcn/Base UI, Tailwind CSS, React Router, Vitest, and ESLint. Reuse components under `compensation/dashboard/src/components/ui/`; dependency versions live in the `catalog` of the root `pnpm-workspace.yaml`; use `compensation/dashboard/package.json` for the dependency list and `compensation/dashboard/components.json` for shadcn configuration. Fetcher clients are generated under `compensation/dashboard/src/generated/`. Do not hand-edit generated client files unless the generator input is unavailable and the user accepts that tradeoff.

## Version Management

The project version is the `version` property in `gradle.properties`. Maven and npm release together under that version and one `v<version>` tag. Third-party versions are centralized in `gradle/libs.versions.toml` and the `wow-dependencies` module.

A release is prepared in one pull request (`chore(release): prepare <version>`):

1. `pnpm set-version <version>` writes `gradle.properties` and the version of every `typescript/*/package.json`, `compensation/dashboard/package.json` and `documentation/package.json`. It then lists the tracked files that still mention the old version.
2. Update the ones that track the release by hand: the version tables in `README.md` and `README.zh-CN.md`, `documentation/docs/{en,zh}/guide/existing-project.md` and `getting-started.md`, and `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`.
3. `pnpm check:versions` confirms every `package.json` matches; the `quality` job of `typescript.yml` runs the same check.

Breaking changes, Kotlin or TypeScript, ship only in an `x.Y.0` release; mark them with `!` in the conventional commit. Release admission enforces this. Compatibility code kept until v10 is listed in `docs/compat-debt.md`; `pnpm check:compat-debt` checks its markers.

## CI And Release Workflows

GitHub Actions run module-level checks from `.github/workflows/`:

- `local-test.yml` runs `allLocalTest` and local coverage.
- `contract-test.yml` runs `allContractTest` and contract coverage.
- `integration-test.yml` runs `allIntegrationTest` and integration coverage.
- `compensation-test.yml` checks compensation core and domain modules.
- `example-java-test.yml` builds the Java transfer example modules.
- `codecov.yml` publishes coverage.
- `documentation-deploy.yml`, `example-deploy.yml`, and `compensation-deploy.yml` deploy docs and sample apps.
- `typescript.yml` runs on every pull request; its scope job decides which TypeScript jobs run, and `typescript-gate` is the merge signal for JavaScript changes. `dashboard-test.yml` checks the compensation dashboard.
- `typescript-contract.yml` runs the TypeScript client, generator and integration tests against an example server built from the same commit, and type-checks code generated from the 8.10.8 and 8.11.5 server images; the shared scope script decides which part runs, and `typescript-contract-gate` is its merge signal.
- `package-deploy.yml` publishes when a GitHub Release is created or the workflow is manually dispatched. Its `preflight` job checks that the tag, `gradle.properties` and every `package.json` agree, runs release admission (`.github/scripts/release-admission.mjs`), builds and dry-runs the npm packages, and runs the Gradle build and integration tests. Then `github-deploy` (GitHub Packages), `central-deploy` (Maven Central) and `npm-deploy` run in parallel.
- Release admission requires a successful push or `workflow_dispatch` run of `typescript.yml` on the release commit with `typescript-gate` passing, and refuses a patch release when any commit since the previous `v*` tag is breaking (`!` or a `BREAKING CHANGE:` footer). For a commit that has no push run, dispatch `typescript.yml` on it first.
- `npm-deploy` runs `.github/scripts/publish-npm.mjs`. It publishes `@ahoo-wang/wow-client`, `@ahoo-wang/wow-react` and `@ahoo-wang/wow-generator` with OIDC trusted publishing and provenance, skips a version already on npm (so a failed run can be re-run), and tags a patch to an older line `release-<major>.<minor>` instead of `latest`. Private packages are never published. A new public package must be added to `PUBLISHED` or `HELD_BACK` in that script. Maven modules that are not ready to publish go in `incubatingProjects` in `build.gradle.kts`.

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
