---
title: The Manifest and Compatibility
description: How a service's Manifest is built and merged, what it is used for, how peers get their part of it, and how the compatibility check keeps a change from breaking clients.
outline: deep
---

# The Manifest and Compatibility

A service's Manifest is its contract in one document: its contexts, aggregates, commands, events, state schemas, subscriptions, published streams and error codes. The routes, the OpenAPI document, the generated clients, wait plans and query admission are all derived from it. This page follows it from the compiler to the service, to its peers and to the compatibility check. [One Contract: the Manifest](../concepts/contract) explains why it exists.

## From fragments to the service's Manifest

1. **The compiler** writes each domain module's fragment, `META-INF/wow/manifest/<module>.json` ([What the Compiler Generates](./generated-code#the-manifest-fragment)).
2. **The Gradle plugin** (`generateWowManifestFragment`) completes the fragment with the JSON Schemas of its types, the query keywords and the state's fingerprint. The completed fragment replaces the compiler's in the module's resources.
3. **At build time**, `mergeWowManifest` merges the fragments of the service's runtime class path into `build/wow/manifest.json`.
4. **At startup**, the host merges the same fragments, adds the framework's error codes, and serves the result at `GET /wow/manifest`.

The snapshot queries read the state schema from the completed fragment: a state field can be filtered and sorted only once the plugin has completed it.

## Peers get their part

A service that sends another context's commands, or reads its published stream, needs that context's commands, events, state and errors. The plugin selects them from the other context's fragment and puts them in its api module's jar, as `META-INF/wow/contract.json`. A service that depends on the api module finds the contract on its class path, and uses it to name the commands it sends, check the wait plans that cross into the other context, and decode its published events. Nothing is configured: depending on the api is enough.

A tool that cannot take the api jar, such as a console or a gateway, reads the peer's Manifest from its `GET /wow/manifest` instead ([Call Other Services](./calling-services#call-a-command-by-name)).

## The compatibility check

Each service commits its merged Manifest as a baseline, `manifest/baseline.json`. The baseline records the service's compatibility line, the major of its version (`"line": 10`), not the version, which moves with every release. `checkWowManifest`, part of `check`, compares every build's merged Manifest with it and sorts each difference:

| Kind | Examples | In `check` |
| --- | --- | --- |
| Additive | A new command, event or aggregate; a new optional field | Fails until the baseline records it |
| Breaking | A removed command or event; a new required field; a field removed or retyped; an event's schema changed without a new revision and upgrade | Fails the build within the line, listing each change and its path |

`build/wow/manifest-compatibility.txt` lists every change, additive ones too. A missing baseline fails `check` too. After an intended change, `updateWowManifestBaseline` records the new baseline: commit it with the change, so its review shows what clients and peers will see. Each failure names that task. The eShop regenerates its TypeScript clients from the baselines, so a client and its service never drift apart unnoticed.

A breaking change needs the service's next major version, or a new command or event beside the old one, the old one retired once its last client has moved. While the service's major equals the baseline's line, `checkWowManifest` fails a break and `updateWowManifestBaseline` refuses to record it. Once the version's major is greater than the line, the update records the break and the new line in the same change. A pre-release of a major's first version, such as `11.0.0-beta.1`, keeps its line open: it records a break, since nothing has shipped on that line yet. Any other pre-release records a break only with `-Pwow.manifest.allowBreaking`, and the update's report, `build/wow/manifest-baseline.txt`, logs the flag and the changes it let through.

## What reads the Manifest

| Reader | Uses it for |
| --- | --- |
| The host | Its routes, wait plans, query admission (ownership, schemas), the internal port's call permissions |
| The OpenAPI document | Every route, its request and response schemas ([OpenAPI and springdoc](./openapi)) |
| The TypeScript generator | Typed command and query clients |
| The view engine | The fields and capabilities a view may use |
| Peers | Their contract with this context, through its api jar |
| The ops console and migrate-data | Commands, subscriptions and types by name |
