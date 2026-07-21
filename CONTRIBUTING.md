# Contributing to Wow

Thank you for helping improve Wow. Contributions may include bug reports, feature proposals, documentation, tests, examples, and code changes.

## Before You Start

- Search the open and closed [issues](https://github.com/Ahoo-Wang/Wow/issues) to avoid duplicates.
- Use the bug, feature, or question template so the issue receives an initial classification.
- Discuss breaking changes, public API changes, generated contracts, new dependencies, and module-boundary changes in an issue before implementation.
- Report vulnerabilities privately according to the [security policy](SECURITY.md).

## Development Setup

The JVM build requires JDK 17. Use the Gradle wrapper from the repository root:

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew detekt
```

Use the narrowest module task that covers the change. For example:

```bash
./gradlew :wow-core:test
./gradlew :wow-core:test --tests "me.ahoo.wow.command.CommandGatewayTest"
./gradlew :example-domain:test --tests "me.ahoo.wow.example.domain.order.OrderSpec"
```

For the compensation dashboard:

```bash
cd compensation/dashboard
pnpm install
pnpm test
pnpm lint
pnpm build
```

For the documentation site:

```bash
cd documentation
pnpm install
pnpm docs:build
```

See the [contributor onboarding guide](https://wow.ahoo.me/onboarding/contributor-guide.html) for architecture, module boundaries, testing patterns, and additional commands.

## Change Guidelines

- Keep API contracts in `wow-api`, runtime behavior in `wow-core`, Spring integration in `wow-spring*`, and infrastructure concerns in their dedicated modules.
- Preserve reactive `Mono` and `Flux` paths; do not introduce blocking calls into core runtime flows.
- Add or update tests for behavior changes and defect fixes.
- Prefer the existing Wow test DSL and FluentAssert conventions in Kotlin tests.
- Do not edit generated dashboard clients under `compensation/dashboard/src/generated/` when the generator source can be fixed.
- Keep generated output, credentials, signing keys, tokens, IDE state, `.gradle/`, and `node_modules/` out of commits.
- Follow the existing conventional commit style, for example `fix(core): handle empty event stream`.

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
