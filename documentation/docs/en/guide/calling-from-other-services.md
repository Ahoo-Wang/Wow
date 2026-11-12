---
title: Call Wow from Other Services
description: How an application that is not a Wow service calls one, from outside through the gateway, from the JVM with WowClient, from other languages with OpenAPI clients, or asynchronously from the published streams; the headers to send, the error codes, and why to consume streams rather than write command topics.
outline: deep
---

# Call Wow from Other Services

An application that is not a Wow service, such as a backend for a front end, an older service, a job written in another language or a partner's system, reaches a Wow service in one of four ways. Each way goes to one port of the service and calls it as one identity. This page says which way fits which caller, what each request carries, and what comes back. A Wow service calling another uses its own `CommandClient` instead: [Call Other Services](./calling-services).

## Choose the way in

| Caller | Goes to | As |
| --- | --- | --- |
| Outside the platform: a browser, a mobile app, a partner | The gateway, then the service's public routes | The end user, whom the gateway authenticates; the service maps the identity the gateway asserts to its caller |
| A JVM application of the platform | `WowClient`: in user mode the public routes, in service mode the internal port | User mode: the end user, by the credential it forwards. Service mode: the service it names, acting for the caller it sends as |
| An application in another language | A client generated from the service's OpenAPI document, through the gateway | The end user, or the service account the gateway authenticates |
| An application that reacts to what happened | The context's published streams on Kafka | Nobody: it reads, as a consumer group of its own, and sends no command |

The public port is reachable from the gateway alone, and the internal port from the platform's own services alone ([Secure a Deployment](../operate/security)). The internal port takes each caller at its word for the service it names, so only an application of the platform, deployed and reviewed as its services are, calls in service mode. Every other application goes through the gateway, as the end user it acts for.

## From the JVM

`WowClient` sends command objects and reads states, as a Wow service's own clients do. It takes the peer's contract from its api jar, so the application depends on the peer's `*-api` module. Build it with where each context's service is, and its mode:

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.client.EndpointResolver
import me.ahoo.wow.client.UserCredential
import me.ahoo.wow.client.WowClient
import java.net.URI

// In user mode, through the gateway, as the end user whose credential it forwards.
val asUser = WowClient.builder()
    .endpoints(EndpointResolver.of(mapOf("eshop-catalog" to URI.create("https://gateway.internal"))))
    .user(credential)
    .build()

// In service mode, at the internal port, as the service it names.
val asService = WowClient.builder()
    .endpoints(EndpointResolver.of(emptyMap(), mapOf("eshop-catalog" to URI.create("http://catalog.internal:9092"))))
    .service("storefront-app")
    .build()
```

`client.commands` is the same `CommandClient` a Wow service has, and `client.queries` the same `QueryApi`. In service mode the name goes in the `Wow-Service` header of each request, and the called service may list what that name may call (`wow.callers.<service>`).

On a Spring Cloud registry the client finds the service's instances as `lb://<service>`, through Spring Cloud LoadBalancer ([Resolve `lb://`](../operate/spring-cloud#load-balancer)). An `EndpointResolver` of your own that looks instances up (in a registry, DNS or a sidecar) also overrides `reaches` to say which contexts it serves: a lookup of one of them that finds no instance then fails as `wow.peer.unavailable`, which the client retries, and not as a context with no URL, which is the code's mistake and is not retried. Java code wraps the client in `JavaCommandClient` ([From Java](./calling-services#from-java)).

## From other languages

`GET /wow/openapi` serves the OpenAPI 3.1 document of a service's public routes, its internal commands left out ([OpenAPI and springdoc](./openapi)). Generate a client from it with the OpenAPI tool of the language, and point it at the gateway. A TypeScript application generates its client from the Manifest instead, with `wow-generator`, which keeps the wait plans and the query fields OpenAPI cannot describe ([Generated clients](./http#generated-clients)).

## Send the headers

A command is a `POST` of its JSON body to its route, `/<context>/<aggregate>/{id}/<command>`. The headers that make it safe to retry, and say how long to wait:

| Header | Send | Why |
| --- | --- | --- |
| `Wow-Request-Id` | On every command, a new UUID for each command; the same one when you send it again | A retry with the same id is answered as done (`duplicate`), never decided twice |
| `Wow-Wait` | When the default wait, `snapshot`, does not fit | How far the command goes before the answer: `accepted`, `committed`, `snapshot`, `queryable` (the store the aggregate's snapshot queries read has the commit), or a subscription it waits for (`handled(<processor>.<function>)`) |
| `Wow-Wait-Timeout` | When the default 10 seconds does not fit | How long to wait, at most `wow.wait.max-timeout` |
| `If-Match` | When the command must apply to the version you read | A command on another version is refused with 412 |
| `Authorization` | What the gateway authenticates | The gateway turns it into the identity the service reads |

<!-- sample: fragment -->

```bash
curl -X POST https://gateway.example.com/bank/account/alice/deposit \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Request-Id: 01937c2e-7b1a-7c4e-9f0e-2d1b8a6c5e3f' \
  -H 'Wow-Wait: committed' \
  -H 'Wow-Wait-Timeout: 5s' \
  -d '{"amount": 100}'
```

A client that lost an answer sends the command again with the same `Wow-Request-Id`. A new id makes it another command, which runs again. [HTTP API](../reference/http#request-headers) lists every header; `WowClient` sends these itself.

## Read the answer

A command that succeeded answers `200` with its result (`202` when its wait ends at `accepted`). Anything else is an `application/problem+json` problem whose `code` says what happened and whose `retry` says what a retry may do:

| `retry` | Do |
| --- | --- |
| `none` | Do not send it again as it is: fix the request, or tell the user |
| `same-request` | Send it again with the same `Wow-Request-Id`, after `Retry-After` |
| `new-request` | Send it again as a new request, after `Retry-After` |

The codes a caller meets most:

| Code | Status | Means |
| --- | --- | --- |
| `wow.command.invalid` | 400 | The command failed validation; `violations` names each field |
| `wow.auth.unauthenticated`, `wow.auth.forbidden` | 401, 403 | No valid identity, or one that may not send the command |
| `wow.aggregate.not-found` | 404 | No aggregate of that id |
| A domain code, such as `bank.account.insufficient-balance` | 422 by default | The aggregate rejected the command; the code is the domain's contract |
| `wow.aggregate.version-mismatch` | 409, 412 with `If-Match` | The aggregate is not at the version the command expects |
| `wow.command.busy` | 429 | Too many commands waiting; retry later with the same request id |
| `wow.node.closing`, `wow.storage.unavailable` | 503 | A node shutting down, a store out of reach; retry with the same request id |
| `wow.wait.timeout` | 504 | The wait ran out; the command may have committed, so retry with the same request id |

[Error Codes](../reference/error-codes) lists every framework code, and [Rejections and Error Codes](./rejections) how a domain declares its own.

## Consume the published streams

An application that reacts to what happens in a context reads the context's published streams: on Kafka, `wow.<context>.<aggregate>.state` holds one record per commit, with its events and the state after it ([External consumers](./published-streams#external-consumers)). A stream is part of the context's contract, and the Manifest's compatibility check guards its fields as it guards the commands.

To change something in the context, call its HTTP API instead of writing to its command topic `wow.<context>.commands`:

- A record on a command topic runs without admission: the service does not authenticate it, so whoever writes it can name any operator, tenant and scope. The topic is open only to the platform's own services ([Restrict the command topics](../operate/security#restrict-the-command-topics)).
- A record gets no answer: no result, no rejection code, no wait.
- The command topic is how the platform's own services reach a context, not a contract for anyone else.
