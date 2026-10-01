<!--
Release notes for Wow v<version>. The release checklist in typescript/RELEASING.md
says when and how to fill this in. Copy everything below this comment into the
GitHub release body, then:

- Replace every <placeholder>. Delete these HTML comments before you publish.
- Take the raw material from GitHub's "Generate release notes" (categories come
  from .github/release.yml), from the git commands in RELEASING.md, and from the
  "Release notes" / "Breaking" / "Behaviour changes" sections of the merged pull
  requests' descriptions. Don't paste that list as it is: it names PRs, not what
  changed for a user.
- "Breaking" and "Behaviour changes" are required, and they do not overlap.
  "Breaking" holds every change that breaks someone: TypeScript, Kotlin / JVM,
  and under "Server behaviour" the approved exceptions to the v9 REST, storage
  and wire freeze and the deployment steps a new node needs. "Behaviour
  changes" holds what users see change but does not break them (an error that
  becomes a 4xx, an empty answer instead of a 503, a template for new indices).
  A patch release has no breaking change, because release admission refuses
  breaking commits in x.y.Z: write "None." under "Breaking". An x.Y.0 lists
  every breaking change, with migration steps; users read these sections
  before they move off their `~x.y` range.
- Release admission refuses an x.Y.0 whose notes do not name (`#number`,
  outside HTML comments) every pull request since the previous release that is
  marked breaking: a `!` title, a BREAKING CHANGE footer, or the
  `breaking-change` label. Name each under "Breaking"; a pre-release change
  that affects nobody goes on the "Pre-release changes" line.
- A section with nothing in it keeps its heading and says "No changes."
-->

## Highlights

<!-- Three to five lines a user would care about, the most important first. Link the docs page for each. -->

- <what a user can do now, in one sentence> ([docs](https://wow.ahoo.me/<page>))

## Breaking

<!--
One entry per breaking change. Say who is affected, then give steps a user can
follow without reading the PR. TypeScript packages first, then Kotlin/JVM.
-->

### TypeScript

#### `@ahoo-wang/<package>`: <what changed> (#<PR>)

**Affects:** <who: e.g. code that imports `X`, generated clients from before 9.2.0>

**Migrate:**

1. <step>
2. <step>

```ts
// Before
<old usage>

// After
<new usage>
```

### Kotlin / JVM

<!--
Public API, SPI (a backend, gateway or request scope a host implements or
extends), Spring bean names a host overrides, configuration keys, and checks
that now fail at startup. Source and binary breaks both count: 9.x nodes run
mixed and libraries compiled against the previous minor run on this one.
-->

- **<what changed>** (#<PR>). **Affects:** <who>. **Migrate:** <step>.

### Server behaviour

<!--
REST, storage and wire behaviour of existing aggregates is frozen within a
major (v9). A change to it ships only as an exception the user approved
(recorded in a decision, e.g. D77), only in x.Y.0, and is listed here with:
what changed, who is affected, the step to take before upgrading, and what
a mixed cluster does while old and new nodes run side by side. A new
privilege or a startup step a node needs goes here too.
-->

#### <what changed> (#<PR>)

<!-- One paragraph: before, after, who notices. -->

**Before upgrading:** <step, or "nothing">.

**Mixed <previous>/<version> cluster:** <what an old node and a new node each do for the same request>.

### Pre-release changes

<!--
Breaking-marked pull requests that changed only code that was never released
(a package's first release, a module first published now). They affect
nobody; list their numbers on one line so the record is complete.
-->

<#PR, #PR, …> changed code that was not released before <version>; nothing to migrate.

## Behaviour changes

<!--
What users see change that breaks nobody: a 5xx that becomes a 4xx, an empty
answer instead of a 503, a template rule for new indices, clearer error
texts. Say whether it needs an action and, where old and new nodes answer
differently, what a mixed cluster does. A change that breaks someone,
including an approved v9 exception, goes under "Breaking" instead. A pull
request's "Behaviour changes" section is raw material for this one and does
not label it breaking.
-->

- <what changed, who notices, whether to act> (#<PR>)

## TypeScript packages

Install or upgrade with a `~<major>.<minor>` range: a minor release may break
(see [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges)).
Every package in `PUBLISHED` (`.github/scripts/publish-npm.mjs`) ships together
at `<version>`; the packages peer on each other with `~`, so upgrade them
together.

### `@ahoo-wang/wow-client`

- <feat/fix in user terms> (#<PR>)

### `@ahoo-wang/wow-react`

- <feat/fix in user terms> (#<PR>)

### `@ahoo-wang/wow-generator`

- <feat/fix in user terms> (#<PR>)

<!-- If generated code changes, tell users to regenerate: `wow-generator generate ...`. -->

### `@ahoo-wang/wow-view-engine`

- <feat/fix in user terms> (#<PR>)

### `@ahoo-wang/wow-view-store`

- <feat/fix in user terms> (#<PR>)

## JVM (Maven)

<!-- Kotlin, Spring Boot starter, view store, compensation and the other Maven modules: new features, fixes, dependency upgrades a user must know about. Docker images (example, compensation, view store server) carry the same version. -->

- <change> (#<PR>)

## Full changelog

<!-- The compare link. For v9.2.0 use v9.1.5...v9.2.0 and add: "The range includes the fetcher history imported into typescript/; the sections above are the changes." -->

https://github.com/Ahoo-Wang/Wow/compare/v<previous>...v<version>
