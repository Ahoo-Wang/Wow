---
title: Call Other Services
description: Send command objects to any context with the typed command client, in process, through a command topic or over HTTP; configure peers, service and user modes, and call commands by name.
outline: deep
---

# Call Other Services

Application code that sends a command, such as a controller, a job or an integration, uses the command client. It takes the command object and the aggregate it is for, and reaches the aggregate wherever it is served: in this service, in a peer context through its command topic, or in a peer over HTTP. The code is the same in each case. This page shows how to send, how to configure where peers are, and what comes back. A saga derives its commands instead: [Write a Saga](./sagas).

## Send a command

The host provides a `CommandClient` bean. Choose the caller with `of(caller)`, then send the command object to the aggregate's ref:

<!-- sample: example/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#send -->

```kotlin
val ref = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-1")
val submitted = basket.bean<CommandClient>().of(alice).wait(SubmitOrder(items), ref)
submitted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
submitted.ref.assert().isEqualTo(ref)
```

The ref comes from the context's generated contract, `EshopOrderingContract.Order.ref(tenant, id)`, in the ordering's api module. A command names no path and no type name: the client finds the command's type from the contracts on the class path.

| Call | Answers |
| --- | --- |
| `send(command, ref)` | The result once the command is accepted; for a peer's command, once its topic acknowledged it |
| `wait(command, ref)` | The result of the command's wait plan, the server's default plan unless the options name one |
| `stream(command, ref)` | A flow of the results of each hop of the wait, the last one the result `wait` answers; collecting the flow sends the command |

`SendOptions.of(...)` sets the rest: the `wait` plan and its `timeout`, the `requestId` that makes the command idempotent (a new one when left out), the `expectedVersion` of the aggregate, and, for a caller allowed to act in another scope, the `targetTenant` and `targetScope`.

A command always has an accountable caller: the client sends as the anonymous caller until `of` gives it another, and there is no trusted mode for commands. A system sender passes its own service caller.

## What comes back

`send` and `wait` return only a result that succeeded. Otherwise they throw:

| Exception | When | Retry |
| --- | --- | --- |
| `CommandRejectedException` | The aggregate rejected the command, or admission did (unknown, malformed or invalid command, forbidden caller). `code` is the error code | No: the same request is rejected again |
| `WowProblemException` | A technical failure: a busy or closing node, a store or broker down, a wait that timed out. `code` and `status` say which | As its `retryHint` says: whether to retry, whether with the same request id, and after how long |

`stream` emits every result, whatever its status. [Rejections and Error Codes](./rejections#what-the-caller-receives) shows a rejection caught by its code.

## Where each command goes

| The aggregate | How the client reaches it |
| --- | --- |
| Of this service | In process, without encoding the command |
| Of a peer context, by default | Through the peer's command topic, `wow.<context>.commands`; `send` answers once the topic has it |
| Of a peer listed under `wow.clients.contexts` | Over HTTP, to the peer's service |

The client knows a peer from the contract its api jar carries, so the application depends on the peer's `*-api` module and nothing more.

## Configure a peer over HTTP

A peer listed under `wow.clients.contexts.<context>` is reached over HTTP, in one of two modes:

| Mode | Reaches | As |
| --- | --- | --- |
| `service`, the default | The peer's internal port, at `internal-url` | This service, with its service credential, acting for the caller it sends as |
| `user` | The peer's public routes, at `url` | The end user, forwarding the headers of the request being served (`wow.clients.forwarded-headers`, `Authorization` by default) |

In service mode the client presents the service token, `wow.calls.token`, which the peer lists for this service under its `wow.http.services`; the peer then trusts the caller this service names. `internal-url` defaults to the URL the sagas' synchronous calls use, `wow.calls.contexts.<context>.url`. The basket service reaches the ordering's internal port this way:

<!-- sample: example/eshop/basket/basket-server/src/main/resources/application.yaml#calls -->

```yaml
# The checkout saga submits the order to the ordering service synchronously (decision D193), through its command
# gateway on its internal port (wow.internal.port there; its public port refuses a call), with this service's
# credential, which the ordering service trusts (wow.http.services.eshop-basket-service.tokens there). The token below is for trying the shop out on one machine:
# a deployment sets ESHOP_BASKET_SERVICE_TOKEN, a secret of at least 32 characters, and rotates it there and here.
calls:
  contexts:
    eshop-ordering:
      url: http://127.0.0.1:9093
  token: ${ESHOP_BASKET_SERVICE_TOKEN:eshop-basket-local-service-token-not-a-secret}
```

`wow.clients.timeout` bounds each request (30 seconds by default, longer for a command whose wait is longer), and `wow.clients.retry.*` retries what a retry may get past. [Secure a Deployment](../operate/security#close-the-internal-port) explains the service identities a peer accepts.

## Call a command by name

A console, a gateway or a tool may call a peer without its api jar. It lists the peer with `wow.clients.contexts.<context>.contract: manifest` and sends a `NamedCommand`: the command's full type name, `context.aggregate.Command`, and its JSON body. The client reads the peer's Manifest from `GET /wow/manifest` and refuses a command the Manifest does not declare before sending anything.

## Outside a Wow host

An application that is not a Wow service builds the clients itself with `WowClient.builder()`: where each context's service is (`endpoints`), the mode and credential (`service` or `user`), and timeouts. `client.commands` is the same `CommandClient`, and the peers' contracts come from the api jars on the class path.

## From Java

The client's functions are `suspend` functions. Java code wraps the client in `JavaCommandClient`, whose calls are blocking, or asynchronous with a `CompletableFuture`. Call the blocking forms from your own threads, never from a Netty or WebFlux event loop.
