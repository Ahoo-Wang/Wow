---
title: Host without Spring
description: What of Wow runs without Spring in 10.0, a domain, its tests and the clients of other services, and why serving a service still takes the Spring Boot host.
---

# Host without Spring

Wow's runtime does not depend on Spring: the kernel, the runtime and the backends are plain Kotlin, and Spring Boot is one host that composes them. In 10.0 it is the only supported host. This page says what runs without it and what does not.

## Without Spring

- **A domain module.** The api and domain modules a service is made of depend on `wow-api` and `wow-kernel`, and the models the compiler generates for them use no Spring type. A domain jar can be shared with any JVM application.
- **Its tests.** `aggregateSpec`, `sagaSpec` and the processor specs of `wow-test` run the generated models on in-memory stores, in a plain JUnit test ([Testing](./testing)).
- **Calling other services.** `WowClient` sends commands to the peers named in their `*-api` jars and reads their snapshots, events, datasets and states over HTTP, on the JDK's `HttpClient`, with no Spring on the class path:

<!-- sample: fragment -->

```kotlin
val client = WowClient.builder()
    .endpoints(EndpointResolver.of(emptyMap(), mapOf("ordering" to URI.create("http://ordering:9090"))))
    .service(ServiceCredential.bearer(token))
    .build()
client.commands.of(caller).wait(SubmitOrder(items), OrderingContract.Order.ref(tenant, orderId))
```

  Java code takes it through `JavaCommandClient.of(client.commands)` and `JavaQueryApi.of(client.queries)` ([Calling Other Services](./calling-services)).

## What still takes the host

Serving a service, its routes, its node with the subscriptions, the timers and the cluster, and its read side, is a composition of many parts, and in 10.0 the classes that compose it are internal API (`@InternalWowApi`): the service node, the query engine and the read facade that serves state reads and queries. An application cannot build a node from them without opting into internal API, which no release promises to keep ([API Levels](../reference/api-levels)). The HTTP routes, `wow-http`, are built on Spring WebFlux.

So a service runs on the Spring Boot host ([Host on Spring Boot](./spring-boot)), which maps its configuration onto those parts and owns their lifecycle. A public composition API for other hosts may come in a later 10.x release; until then, an application that cannot use Spring Boot keeps its domain and its clients on Wow and serves through a Spring Boot service of its own beside it.
