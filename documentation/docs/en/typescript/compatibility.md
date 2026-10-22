---
title: Compatibility and Versions
description: Which Wow servers, Node.js, React, TypeScript and Fetcher versions the Wow TypeScript packages work with, and how to keep their versions in step.
---

# Compatibility and Versions

## Release status

The packages of Wow 10 are published with every Wow 10 release, from CI and with [npm provenance](https://docs.npmjs.com/generating-provenance-statements). A pre-release goes to the dist-tag of its kind, `alpha`, `beta` or `next` for a release candidate (`pnpm add @ahoo-wang/wow-client@alpha`); `latest` names the newest stable release.

## Wow servers

A package of Wow 10 talks to a Wow 10 service, and generates from a Wow 10 Manifest. The routes, the headers, the waits, the problems and the query language changed from 9.x, so a 9.x service needs the 9.x packages, and a front end moves to 10 with its services ([Migrating from 9.x](../migrate/)).

Within 10, use the packages of the version of the services a front end calls. A service that is newer adds what its Manifest adds: generate again to see it. A field the generated types do not name still arrives in the JSON, and the types ignore it.

## Versions

The packages share one version with the Kotlin modules and are released from the same tag. Breaking changes ship only in an `x.Y.0` release and are listed under "Breaking" in the [release notes](https://github.com/Ahoo-Wang/Wow/releases).

- **Use one version for every Wow package of an application.** `wow-generator`, `wow-react` and the view engine take `@ahoo-wang/wow-client` as a peer with `~x.y.z`, so the package manager warns when they differ in minor version.
- **Generate with the generator of the same version** as the `wow-client` the generated code compiles against.

### Version ranges

The version follows Wow, not semver: a minor release (`x.Y.0`) may change the TypeScript API, and only patch releases (`x.y.Z`) are guaranteed compatible. `pnpm add` and `npm install` save a caret range (`^x.y.z`) by default, which lets a later install pick up the next minor without anyone deciding to. Save a tilde range instead, which accepts the patches of one minor only. pnpm reads the setting from `pnpm-workspace.yaml`:

```yaml
savePrefix: '~'
```

npm reads it from the project's `.npmrc`:

```ini
save-prefix=~
```

Or pin exact versions:

```bash
pnpm add --save-exact @ahoo-wang/wow-client
pnpm add -D --save-exact @ahoo-wang/wow-generator
```

Move to the next minor on purpose, every Wow package together, after reading the "Breaking" section of its release notes. The Fetcher peers follow semver, so a caret range suits them: add them with an explicit range (`pnpm add @ahoo-wang/fetcher@^<version>`) when the tilde setting is on.

### Support window

The packages follow the support policy of the rest of Wow, stated in the [security policy](https://github.com/Ahoo-Wang/Wow/blob/main/SECURITY.md): fixes land on the latest stable line, and fixes for older lines are decided case by case.

## Runtimes and peers

| Requirement | Version | Notes |
| --- | --- | --- |
| Node.js | `>=22.12.0` | Declared in `engines` of every package; the generator's CLI needs it too |
| Browsers | Current evergreen browsers | The packages use `fetch`, `ReadableStream` and `TextDecoderStream` |
| React and React DOM (`wow-react`, the view engine's UI) | `^19.0.0` | React 18 is not supported: the build imports `react/compiler-runtime` |
| React Router (`wow-view-engine/react-router` only) | `^7.0.0 \|\| ^8.0.0` | An optional peer: only that entry imports it |
| TypeScript | `>=6.0` | CI type-checks the packed packages as a consumer on TypeScript 6.0 and the latest 7.x; `moduleResolution` `Bundler`, `NodeNext` or `Node16` |
| `@ahoo-wang/fetcher`, `fetcher-eventstream`, `fetcher-decorator` | `^5.1.5 \|\| ^6.0.0` | Peers the application installs. CI builds and tests on the latest 6.x, and a floor job reruns the tests on 5.1.5. `fetcher-decorator` is needed only by clients generated from an OpenAPI document |
| `@ahoo-wang/wow-client` (for the other Wow packages) | `~x.y.z` | The same minor version |

pnpm 8 and later and npm 7 and later install missing peers by themselves; Yarn does not, so a Yarn project adds each peer itself.

## Exact numbers and projected results

A Manifest field of a Kotlin `Long`, `BigInteger` or `BigDecimal` (schema format `int64`, `bigint` or `decimal`) is a `string` in the generated types, with its digits exactly as the service wrote them; the wire still carries JSON numbers ([Generate a Client](./generate#exact-numbers)). 9.x generated `number`.

A query written with literal `projection.include` paths answers records narrowed to those paths; one built with `listQuery` or `pagedQuery`, or with only an exclusion, answers whole records. A calendar filter without a `zoneId` reads its days in UTC ([Read and Query](./queries#relative-time-and-time-zones)).
