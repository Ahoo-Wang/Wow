---
title: Developer Guide
description: How to write a Wow service, by task, from the domain model to the read side, other contexts, the contract, tests and the host.
---

# Developer Guide

These pages show how to do one thing each, with code from the repository's examples. They assume the ideas of [Concepts](../concepts/); every page links to the concept it builds on.

## Model the domain

| Page | Shows how to |
| --- | --- |
| [Write an Aggregate](./aggregates) | Write commands, command functions and state; take services; create aggregates; ask the kernel for results, keys and schedules |
| [Rejections and Error Codes](./rejections) | Refuse a command with a declared code, and read it as a caller, a peer, a saga or a test |
| [Evolve Events](./event-evolution) | Change an event with revisions and upgrade functions; rename and retire events; change the state's structure |
| [Owners, Spaces and Tags](./ownership) | Decide who an aggregate belongs to, check the caller, transfer owners, tag aggregates |
| [Unique Keys](./unique-keys) | Keep a value unique across aggregates |

## Processes

| Page | Shows how to |
| --- | --- |
| [Write a Saga](./sagas) | Derive commands from events; act on their success and rejections; call another context |
| [Projections and Reactors](./projections) | Write a read model; act on the outside world; keep both idempotent |
| [Scheduled Commands](./scheduling) | Schedule a command from a decision or a saga; run recurring jobs |
| [Failures and Retries](./failures) | Give a handler its own retry budget; classify failures; retry parked aggregates |

## The read side

| Page | Shows how to |
| --- | --- |
| [Read State](./reading-state) | Read an aggregate's state at any version or instant, cache it, read your own writes |
| [Query Snapshots](./queries) | Find aggregates by their state, in Kotlin and over HTTP; declare indexes and masks |
| [Query Event Streams](./event-queries) | Query the log, one row per commit; analyze events |
| [Publish Datasets from a Service](./datasets) | Declare a dataset over the warehouse, scoped and masked, and query it |

## Across contexts

| Page | Shows how to |
| --- | --- |
| [Publish and Consume Streams](./published-streams) | Choose what a context publishes; read another context's stream with its own classes |
| [Call Other Services](./calling-services) | Send commands with the typed client, in process, through a topic or over HTTP |

## The contract

| Page | Shows how to |
| --- | --- |
| [Serve and Call the HTTP API](./http) | Authenticate callers, send commands, wait, retry, stream, read errors |
| [The Manifest and Compatibility](./manifest) | Build and merge the contract, give peers their part, keep changes compatible |
| [OpenAPI and springdoc](./openapi) | Get the OpenAPI document, merge it into springdoc's |
| [What the Compiler Generates](./generated-code) | Know the model factories, handlers, query fields, binding index and contract code |

## Test

| Page | Shows how to |
| --- | --- |
| [Test Domain Code](./testing) | Test aggregates, sagas, projections, reactors and jobs with the test DSL |
| [Test a Service](./testing-services) | Test a whole service in its JVM, on real stores, and against its contract |

## Host

| Page | Shows how to |
| --- | --- |
| [Host on Spring Boot](./spring-boot) | Run a service: what the host assembles, which backend, which beans |
| [Write the Domain in Java](./java) | Write the domain in Java, and what Java cannot do yet |
