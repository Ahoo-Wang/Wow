---
title: TypeScript
description: The Wow TypeScript packages, what each one does, which a front end needs, and the versions and peers they take.
---

# TypeScript

A Wow service's routes, waits, problems and query language are typed in TypeScript by the packages under [`typescript/`](https://github.com/Ahoo-Wang/Wow/tree/main/typescript). A front end generates one client per aggregate from the service's Manifest and calls the service through it; nothing about a route, a header or an error is written by hand.

## The packages

| Package | Does | Needs |
| --- | --- | --- |
| `@ahoo-wang/wow-client` | Commands with their waits, state reads, snapshot, event and dataset queries, the Manifest, and the problems a service answers with. The query DSL is its `/dsl` entry, the service routes its `/routes` entry | `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-eventstream` |
| `@ahoo-wang/wow-generator` | The typed clients of a service, generated from its Manifest; also models and clients from any OpenAPI 3 document. CLI `wow-generator` | `wow-client` |
| `@ahoo-wang/wow-react` | React hooks that keep a query, its result and its loading state | `wow-client`, React 19 |
| `@ahoo-wang/wow-view-engine` | Configurable data views over Wow data: records, analyses and dashboards | `wow-client`, React 19 |
| `@ahoo-wang/wow-view-store` | The view engine's saved views, kept on a Wow server | `wow-view-engine`, `wow-client` |

A front end that sends commands and reads state needs `wow-client` and, at build time, `wow-generator`. The others are for React components and configurable views.

Every package builds on [Fetcher](https://fetcher.ahoo.me/), a general HTTP client with its own repository. A request goes through a Fetcher the application configures once, with the service's base URL and the interceptors that sign the request.

## Versions and peers

The packages are released with Wow, from the same tag and with the same version: `@ahoo-wang/wow-client` 10.0.0 belongs to Wow 10.0.0. Use one version for every Wow package of an application, the version of the services it calls. The version follows Wow rather than semver: a minor release may change the TypeScript API, so keep the packages on one minor with a `~` range or exact versions.

| Requirement | Range |
| --- | --- |
| Node.js, for the generator and server rendering | `>=22.12.0` |
| TypeScript | 6 or later; CI checks the packages on 6.0 and the latest 7.x |
| `@ahoo-wang/fetcher`, `fetcher-eventstream` (and `fetcher-decorator` for OpenAPI clients) | `^5.1.5 \|\| ^6.0.0` |
| React and React DOM, for `wow-react` and the view engine's UI | `^19.0.0` |
| `@ahoo-wang/wow-client`, for the other Wow packages | the same minor, `~x.y.z` |

Every peer is installed by the application, so it owns each version.

## Install

```bash
pnpm add @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream @ahoo-wang/wow-client
pnpm add -D @ahoo-wang/wow-generator typescript
```

## Continue by task

| Task | Read |
| --- | --- |
| Generate the clients of a service | [Generate a Client](./generate) |
| Send a command and wait for what it leads to | [Send Commands](./commands) |
| Read an aggregate's state, query snapshots and events | [Read and Query](./queries) |
| Build views users configure | [View Engine](./view-engine/) |
| Look up a package's API | [TypeScript reference](../reference/typescript/) |

The routes, headers and problems the clients send and read are listed in the [HTTP API](../reference/http), and the queries in the [Query Language](../reference/query-language).
