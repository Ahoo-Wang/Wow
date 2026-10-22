# Contributing to Wow

Thank you for helping improve Wow. Contributions may include bug reports, feature proposals, documentation, tests, examples, and code changes.

## Before You Start

- Search the open and closed [issues](https://github.com/Ahoo-Wang/Wow/issues) to avoid duplicates.
- Use the bug, feature, or question template so the issue receives an initial classification.
- Discuss breaking changes, public API changes, generated contracts, new dependencies, and module-boundary changes in an issue before implementation.
- Report vulnerabilities privately according to the [security policy](SECURITY.md).

## Development Setup

`main` holds Wow 10. The 9.x line is maintained on the [`v9`](https://github.com/Ahoo-Wang/Wow/tree/v9) branch: a 9.x fix goes there.

The JVM build requires JDK 21. Use the Gradle wrapper from the repository root, with the module paths of `settings.gradle.kts` (`:api`, `:kernel`, `:runtime`, `:spring-boot`, `:eshop-catalog-domain`, …):

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew <module>:integrationTest
./gradlew :check
./gradlew detekt
```

Use the narrowest task that covers the change. `:check` compiles every module and runs its unit tests, detekt, the ABI checks and the architecture test; container-backed tests run with `integrationTest` and need Docker. [Test Layers](documentation/docs/en/contribute/testing.md) says which tests a change needs.

```bash
./gradlew :kernel:test
./gradlew :kernel:test --tests "me.ahoo.wow.kernel.*"
./gradlew :eshop-ordering-domain:check
./gradlew :postgres:integrationTest --stacktrace
```

JavaScript projects (the TypeScript packages under `typescript/`, the apps `platform/ops/console` and `example/eshop/web`, and the documentation site) share one pnpm workspace rooted at the repository root. Use Node.js 22.12 or later and install once from the root:

```bash
pnpm install
```

For the TypeScript packages, run the workspace scripts from the root; package-specific rules are in [typescript/AGENTS.md](typescript/AGENTS.md):

```bash
pnpm build:typescript
pnpm test
pnpm lint
pnpm typecheck
```

The apps consume the workspace packages through their `dist`, so build them first and again after changing them:

```bash
pnpm --filter wow-ops-console^... build
cd platform/ops/console
pnpm test
pnpm lint
pnpm build
```

For the documentation site:

```bash
cd documentation
pnpm docs:build
```

[Codebase Tour](documentation/docs/en/contribute/index.md) maps the repository and its rules; the [design records](documentation/docs/en/contribute/design-records.md) explain why it is shaped that way.

## Change Guidelines

- Keep each concern in its module: the programming model in `:api`, the decide, evolve and append loop in `:kernel`, the node in `:runtime`, Spring wiring in `:spring-boot` only, storage and transport in their backends. `:architecture` holds the dependency rules; a change to a module boundary is discussed first.
- The core is coroutines: `suspend` and `Flow`. Reactor appears only in `:reactor` and the WebFlux edge. Never block a thread in the core's paths; a blocking call belongs behind `@Blocking` or a backend's own dispatcher.
- Give every declaration its [API level](documentation/docs/en/reference/api-levels.md): `internal` when only its module uses it, `@InternalWowApi` when Wow's modules share it, `@ExperimentalWowApi` for a surface 10.x may still change. A published module keeps its ABI in `<module>/api/<module>.api`; when `:check` reports a changed signature, run `./gradlew updateKotlinAbi`, read the diff and commit it. Until 10.0.0 GA a removed or changed line is allowed, labelled `breaking-change` and explained in the pull request; [Binary Compatibility](AGENTS.md#binary-compatibility) gives the rules from GA on.
- Wow keeps no compatibility code: a replaced API goes in the same release, with no `@Deprecated` bridge or `compat(...)` marker; `:architecture` refuses them. A breaking change, to the public API, HTTP behaviour, configuration, or a storage or wire format, ships only in an `x.Y.0` release and says who is affected and how to migrate in a `## Breaking` section.
- Add or update tests for behaviour changes and defect fixes, at the narrowest layer that covers them.
- Put documentation where its readers look for it and update it in the same change: user-facing pages in `documentation/docs/{en,zh}/`, module usage in the module README, design records in `documentation/designs/`. Samples on the site come from compiled code through `<!-- sample: … -->` markers, and the reference pages generated from the code are regenerated with `pnpm docs:reference`. Plans, review notes and scratch evidence stay out of the repository; see [Documentation Governance](documentation/docs/en/contribute/documentation.md).
- Prefer the Wow test DSL and FluentAssert conventions in Kotlin tests.
- Do not edit generated clients under an app's `src/generated/` when the Manifest or the generator can be fixed.
- Keep generated output, credentials, signing keys, tokens, IDE state, `.gradle/`, and `node_modules/` out of commits.
- Follow the existing conventional commit style, for example `fix(kernel): handle an empty event stream`.

## Commenting Guidelines

Comments are part of the maintained code. Keep them concise, accurate, and focused on information that the code cannot express clearly on its own. Follow neighboring source, the rules below, and the repository's current lint configuration rather than a separate comment-style document.

- Write source-code comments and API documentation in English. User-facing documentation follows the language of the page.
- Use KDoc or Javadoc for public contracts whose behavior, constraints, failure modes, or usage are not obvious from the declaration. Do not add documentation that merely repeats a symbol name or type.
- Use `//` for implementation rationale, invariants, compatibility constraints, and non-obvious edge cases. Describe why the code takes an approach rather than narrating each operation.
- Reserve block comments for KDoc, Javadoc, license headers, or explanations that genuinely need multiple paragraphs. Do not keep disabled code in comments; rely on version control.
- Keep documentation next to the declaration it describes. Update or remove it in the same change whenever behavior changes.
- Use Markdown links such as `[EventLog]` in KDoc. Keep `@param`, `@property`, `@return`, and `@throws` tags accurate and avoid redundant tags.
- Do not commit inline task markers such as `TODO`, `FIXME`, `HACK`, or `STOPSHIP`. Track deferred work in the issue tracker and use `Issue #1234: explain the constraint.` only when the code needs that context.
- Preserve the existing Apache 2.0 header in source files that already carry it. New hand-written source files should copy the header used by neighboring files.
- Do not edit generated comments under paths such as `platform/ops/console/src/generated/`; fix their schema or generator input instead.

Detekt checks Kotlin KDoc consistency and task markers. Comment language and semantic quality remain review requirements because automated natural-language classification is not reliable enough for enforcement.

These checks intentionally do not require comments on every declaration: unnecessary comments are noise, not documentation.

## Pull Requests

1. Create a focused branch from `main`.
   Use a recognized prefix when practical: `fix/`, `bugfix/`, `feature/`, `feat/`, `perf/`, `breaking/`, `chore/`, `build/`, `ci/`, or `docs/`. Dependency automation uses `renovate/` and `dependabot/`.
2. Keep the change small enough to review and avoid unrelated formatting or refactoring.
3. Run the narrowest relevant tests, static analysis, build, or documentation checks.
4. Complete the pull request template, including verification evidence and compatibility risks.
5. Link the issue the pull request resolves when one exists.
6. Address review comments and keep the branch current before merge.

## Issue and Pull Request Labels

The project uses a small shared vocabulary so issues and release notes remain consistent:

| Label | Purpose |
|---|---|
| `bug` | Confirmed or suspected defect |
| `enhancement` | New capability or improvement |
| `question` | Usage or design question |
| `documentation` | Documentation-only change |
| `performance` | Performance improvement |
| `breaking-change` | Change requiring migration |
| `maintenance` | Build, tooling, or repository maintenance |
| `dependencies` | Dependency update |
| `good first issue` | A scoped task suitable for a new contributor |
| `ignore-for-release` | Change intentionally omitted from generated release notes |

Maintainers may add more specific labels during triage.

## Community Expectations

Participation in the project is governed by the [Code of Conduct](CODE_OF_CONDUCT.md). Be constructive, assume good intent, and keep technical disagreements focused on evidence and trade-offs.
