---
title: "One Contract: the Manifest"
description: What the Manifest describes, how the compiler builds it, what is derived from it, and how it keeps clients and other services from breaking.
---

# One Contract: the Manifest

A service has many readers: its own runtime, HTTP clients, a TypeScript front end, other services, the view engine, operators' tools, agents. If each of them had its own description of the service, they would drift apart. Wow keeps one description, the Manifest, and derives everything else from it.

## What the Manifest describes

The Manifest is a JSON document the compiler builds from your domain model. For each bounded context of the service it lists:

- the **aggregates**: the schema of each state, whether snapshots are kept, who an aggregate belongs to, and the lifecycle commands it handles;
- the **commands**: name, schema, creation rule, the events each can produce, the error codes it can reject with, and its route;
- the **events**: name, former names, revision and schema;
- the **queries** each aggregate offers;
- the **subscriptions**: each projection, saga and reactor function, what it reads and, for a saga, the commands it sends;
- the **published streams**: which aggregates are published, on which topic, with or without state;
- the **error codes**, with their HTTP status and description.

The schemas are JSON Schema. Names are stable model names, `context.aggregate.Name`, not class names.

## How it is built

```text
annotations ─▶ compiler ─▶ Manifest ─┬▶ HTTP routes (at run time)
                                     ├▶ OpenAPI
                                     ├▶ TypeScript client (wow-generator)
                                     ├▶ views and their capabilities
                                     ├▶ peer contracts for other services
                                     └▶ agent Skills
```

A service's domain is often spread over several Gradle modules. The compiler sees one module at a time, so each module produces a **fragment** of the Manifest. At build time, a Gradle task completes each fragment's schemas and merges the fragments into the service's Manifest. At run time, the service merges the fragments it finds on its classpath. Two fragments that declare the same context, aggregate, command, event or subscription stop the service from starting.

The schemas come from Jackson itself. The build loads the compiled classes and asks Jackson how it would serialize them, so naming strategies, `@JsonProperty`, custom serializers and Kotlin defaults all show up as they really are on the wire. This happens at build time only; at run time nothing is introspected.

The service publishes its Manifest at `GET /wow/manifest` on its public port, authorized like any other route.

## What is derived from it

- **HTTP routes.** The runtime builds the command, query and state routes from the Manifest. There are no route annotations.
- **OpenAPI.** The service serves an OpenAPI document derived from the Manifest. In a Spring Boot host with springdoc, the Wow routes are merged into springdoc's documents as well.
- **TypeScript client.** `wow-generator` reads the Manifest and generates a typed client: commands, their errors, query fields, events. The front end commits the generated code and regenerates it in CI.
- **Views.** The view engine derives default views and what a view may ask for from the Manifest.
- **Admission.** The runtime decodes a command by its type name, checks a wait plan against the subscriptions, and maps error codes to HTTP status, all from the Manifest.

Datasets are not in the Manifest. The Manifest describes what the code defines at build time; datasets are defined at run time in the analytics catalog. The view engine reads both.

## Contracts between services

A service that sends commands to another context, or reads its published stream, needs part of that context's contract: its commands, events, state and errors. It gets that part from the other context's API jar.

When the other context's domain module is compiled, a build task selects what a peer needs from its fragments and puts it into its API jar, as `META-INF/wow/contract.json`. A service that depends on that API jar finds the contract on its classpath at run time. It uses it to check a command before sending it, to check a wait plan that crosses into the other context, and to check a published stream it subscribes to when it starts. There is nothing to configure: depending on the other context's API is enough.

## Compatibility

The Manifest is the contract clients and other services rely on, so its changes are checked. A service keeps its Manifest as a baseline in its repository, with its compatibility line, the major of its version, instead of the version. The build compares the current Manifest with it and sorts each difference:

- **Additive**, such as a new command, a new optional field, or an event that is removed while its name stays retired: allowed, once the baseline records it.
- **Breaking**, such as removing a command or an event, adding a required field, or removing or retyping an event's field, whatever its revision: the build fails while the service's major is the baseline's line. The service's next major records the break and its new line in the baseline; so does a pre-release of a major's first version, such as `10.0.0-beta.1`, whose line is still open.

A new revision exempts only the service's own storage. Once an event or a state has outside readers, through event queries, snapshot queries or a published stream, removing or retyping one of its fields is still breaking.

Binary compatibility of the Kotlin API is a different matter, checked by the ABI dumps. The wire contract is held by the Manifest check.
