---
title: Call Other Services
description: Send command objects to any context with the typed command client, in process, through a command topic or over HTTP; configure peers, service and user modes, and call commands by name.
outline: deep
---

# Call Other Services

Application code that sends a command, such as a controller, a job or an integration, uses the command client. It takes the command object and the aggregate it is for, and reaches the aggregate wherever it is served: in this service, in a peer context through its command topic, or in a peer over HTTP. The code is the same in each case. This page shows how to send, how to configure where peers are, and what comes back. A saga derives its commands instead: [Write a Saga](./sagas).

## Send a command

The host provides a `CommandClient` bean. Choose the caller with `of(caller)`, then send the command object with the aggregate's id:

<!-- sample: examples/eshop/e2e/src/integrationTest/kotlin/me/ahoo/wow/example/eshop/e2e/EshopClientTest.kt#send -->

```kotlin
val ref = EshopOrderingContract.Order.ref(Caller.DEFAULT_TENANT, "client-alice-1")
val submitted = basket.bean<CommandClient>().of(alice).wait(SubmitOrder(items), "client-alice-1")
submitted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
submitted.ref.assert().isEqualTo(ref)
```

A command names no path and no type name: the client finds the aggregate type from the command's class, in the service's models and the contracts on the class path. A creation names no id, and the server assigns one.

| Call | Answers |
| --- | --- |
| `send(command, id)` | The result once the command is accepted; for a peer's command, once its topic acknowledged it |
| `wait(command, id)` | The result of the command's wait plan, the server's default plan unless the request names one |
| `stream(command, id)` | A flow of the results of each hop of the wait, the last one the result `wait` answers; collecting the flow sends the command |

Each takes a `CommandRequest` too, `CommandRequest.of(command, id, ...)` or `CommandRequest.to(ref, command)`, which sets the rest: the aggregate `type` (a lifecycle command such as `DeleteAggregate`, which every aggregate takes, always names it), the `wait` plan and its `timeout`, the `requestId` that makes the command idempotent (a new one when left out), the `expectedVersion` of the aggregate, and, for a caller allowed to act in another scope, the `targetTenant` and `targetScope`. The tenant and scope are otherwise the caller's: a target tenant that is the caller's own changes nothing.

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
| Of a peer listed under `wow.peers` | Over HTTP, to the peer's service |

The client knows a peer from the contract its api jar carries, so the application depends on the peer's `*-api` module and nothing more.

## Configure a peer over HTTP

A peer listed under `wow.peers.<context>` is reached over HTTP, in one of two modes:

| Mode | Reaches | As |
| --- | --- | --- |
| `service`, the default | The peer's internal port, at `url` | This service, naming itself, acting for the caller it sends as |
| `user` | The peer's public routes, at `public-url` | The end user, forwarding the headers of the request being served (`wow.peer.forwarded-headers`, `Authorization` by default) |

In service mode the client names this service, its `wow.service`, in the `Wow-Service` header. The internal port is reachable from the platform's own pods alone, so the peer takes the caller this service names; the commands and models the peer lists for this service under its `wow.callers`, if any, limit what it may call and read. The sagas' synchronous calls go to the same `url`, and `wow.peer.mode` (or a peer's own `mode`) picks the mode. The basket service reaches the ordering's internal port this way:

<!-- sample: examples/eshop/basket/basket-server/src/main/resources/application.yaml#calls -->

```yaml
# The checkout saga submits the order to the ordering service synchronously, through its command
# gateway on its internal port (wow.internal.port there; its public port refuses a call), naming this service
# (Wow-Service), which the ordering service lists the commands of (wow.callers there).
peers:
  eshop-ordering:
    url: http://127.0.0.1:9093
```

Without a `url`, the client finds the peer's nodes by DNS (`wow.peer.discovery: dns`, the default): it looks up the SRV records `wow.peer.dns.name-template` names after the peer's service, `_internal._tcp.{service}-cluster`, the headless Service of its internal port on Kubernetes, `{service}` being the service its contract on the class path names, which its domain module declares (`wow { service }`); a contract that names none leaves the peer to its `url`. The requests go to its nodes in turn; a node that does not take the connection, or that answers it is shutting down or on standby, is passed over for the next one in the same attempt. The records are looked up again every 30 seconds, and at once when no node takes a connection. `wow.peer.discovery: static` takes the `url`s alone, and a community provider of `PeerDiscovery` may find them otherwise. A saga's call needs no listing either: a context whose contract is on the class path is found the same way.

`wow.peer.timeout` bounds each request (30 seconds by default, longer for a command whose wait is longer), and `wow.peer.retry.*` retries what a retry may get past. [Secure a Deployment](../operate/security#close-the-internal-port) explains what a peer accepts from a service that names itself.

## Call a command by name

A console, a gateway or a tool may call a peer without its api jar. It lists the peer with `wow.peers.<context>.contract: manifest` and sends a `NamedCommand`: the command's full type name, `context.aggregate.Command`, and its JSON body. The client reads the peer's Manifest from `GET /wow/manifest` and refuses a command the Manifest does not declare before sending anything.

## Outside a Wow host

An application that is not a Wow service builds the clients itself with `WowClient.builder()`: where each context's service is (`endpoints`), the mode (`service` with the name of the service it calls as, or `user` with the end user's credential), and timeouts. `client.commands` is the same `CommandClient`, and the peers' contracts come from the api jars on the class path.

## From Java

The client's functions are `suspend` functions. Java code wraps the client in `JavaCommandClient`, whose calls are blocking, or asynchronous with a `CompletableFuture`. Call the blocking forms from your own threads, never from a Netty or WebFlux event loop.
