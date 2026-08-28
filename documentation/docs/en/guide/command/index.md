---
title: Commands
description: Enter Wow commands through definition, in-process sending, HTTP invocation, service clients, completion semantics, and troubleshooting.
outline: deep
---

# Commands

A command expresses one business intent to change aggregate state. Define its payload and target aggregate first, then send it through an in-process `CommandGateway`, an aggregate HTTP route, or the global command facade, and wait only for the completion stage the caller actually needs.

## Quick Paths

| Goal | Entry point |
| --- | --- |
| Define and send a command | Read [Define Commands](./definition.md), then [Send Commands](./sending.md) |
| Call another service | Use the [API Client](./api-client.md) with the global command facade |
| Choose a completion stage | Read “Completion semantics” (`command/completion`) |
| Diagnose timeouts, duplicate requests, and downstream failures | Read “Command reliability” (`command/reliability`) |

## Application Use

1. Use [Define Commands](./definition.md) to establish the payload, target aggregate, and handler.
2. Use [Send Commands](./sending.md) to choose among the local Gateway, aggregate routes, and the global facade.
3. For service-to-service calls, use the [API Client](./api-client.md) and accept its current final-result-only boundary.
4. Use “Completion semantics” (`command/completion`) to choose the earliest stage that satisfies the response contract.

This track answers how an application invokes commands. It does not expand Dispatcher, Filter, WaitState, or notifier internals.

## How It Works

To understand the runtime, start with “Command pipeline” (`command/internals/pipeline`), then read the internals pages for wait state and notification. They own the sequence from Gateway through aggregate processing, event append, and stage signals; application pages consume those results without duplicating the state machine.

For failures, return to “Command reliability” (`command/reliability`) and collect evidence by `commandId`, `requestId`, aggregate identity, `stage`, and error code.
