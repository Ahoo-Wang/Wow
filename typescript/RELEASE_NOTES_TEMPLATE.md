<!--
Release notes for Wow v<version>. The release checklist in typescript/RELEASING.md
says when and how to fill this in. Copy everything below this comment into the
GitHub release body, then:

- Replace every <placeholder>. Delete these HTML comments before you publish.
- Take the raw material from GitHub's "Generate release notes" (categories come
  from .github/release.yml) and from the git commands in RELEASING.md. Don't paste
  that list as it is: it names PRs, not what changed for a user.
- "Breaking" is required. Every x.Y.0 with a breaking change in a published
  TypeScript package lists each change with migration steps; users read this
  section before they move off their `~x.y` range. A patch release has none,
  because release admission refuses breaking commits in x.y.Z. Write "None."
  when there are none.
- A section with nothing in it keeps its heading and says "No changes."
-->

## Highlights

<!-- Three to five lines a user would care about, the most important first. Link the docs page for each. -->

- <what a user can do now, in one sentence> ([docs](https://wow.ahoo.me/<page>))

## Breaking

<!--
One entry per breaking change in wow-client, wow-react or wow-generator, whether it
came from a `!` commit or a `BREAKING CHANGE:` footer. Say who is affected, then give
steps a user can follow without reading the PR. Put Kotlin/JVM breaking changes
under their own heading below the TypeScript ones.

A package on the HELD_BACK list (wow-view-engine) is not on npm, so its breaking
changes affect nobody yet: list them in one line under "Not published" instead.
-->

### `@ahoo-wang/<package>`: <what changed> (#<PR>)

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

## TypeScript packages

Install or upgrade with a `~<major>.<minor>` range: a minor release may break
(see [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges)). All
three packages ship together at `<version>`; wow-react and wow-generator peer on
wow-client with `~`, so upgrade them together.

### `@ahoo-wang/wow-client`

- <feat/fix in user terms> (#<PR>)

### `@ahoo-wang/wow-react`

- <feat/fix in user terms> (#<PR>)

### `@ahoo-wang/wow-generator`

- <feat/fix in user terms> (#<PR>)

<!-- If generated code changes, tell users to regenerate: `wow-generator generate ...`. -->

### Not published

- `@ahoo-wang/wow-view-engine` is held back and not on npm yet.

## JVM (Maven)

<!-- Kotlin, Spring Boot starter, compensation and the other Maven modules: new features, fixes, dependency upgrades a user must know about. -->

- <change> (#<PR>)

## Full changelog

<!-- The compare link. For v9.2.0 use v9.1.5...v9.2.0 and add: "The range includes the fetcher history imported into typescript/; the sections above are the changes." -->

https://github.com/Ahoo-Wang/Wow/compare/v<previous>...v<version>
