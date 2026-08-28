---
title: CoSec
description: Extract and propagate CoSec context through Wow WebFlux commands and queries.
---

# CoSec

`wow-cosec` maps `CoSec-*` request headers to Wow command headers/builders and query space filters, then propagates app/device context to downstream messages. Use it only when the application has adopted these CoSec header conventions.

::: danger Security boundary
The module does not authenticate a request, verify header authenticity, authorize commands, or bind tenant/owner/space to a server-side principal. Authentication, route authorization, and fail-closed data access remain application security responsibilities.
:::

## How It Works

Four behaviors form the integration: `CoSecCommandRequestHeaderAppender` extracts app/device, `CoSecCommandBuilderExtractor` supplements request/space, service-loaded `CoSecMessagePropagator` propagates app/device, and `CoSecRewriteRequestFilter` resolves query space. Wow owns context transport only; the security stack owns trusted identity and policy decisions.

## Installation

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("me.ahoo.wow:cosec-support") }
}
```

`cosec-support` brings in `wow-cosec`, which uses `wow-webflux` implementation classes. HTTP routes still require WebFlux auto-configuration to be active. There is no `wow.cosec.*` property tree.

## Auto-Configuration

`CoSecAutoConfiguration` requires Wow to be enabled and `CoSecCommandRequestHeaderAppender` on the classpath, then registers three WebFlux extension beans. It has no `enabled` switch and no `@ConditionalOnMissingBean`. Remove the capability or explicitly exclude the auto-configuration when the behavior is not wanted.

## Usage

Minimum runtime setup is the capability plus the application's authentication/authorization chain; CoSec itself needs no YAML. Before accepting headers, make the gateway remove untrusted external identity headers and let the server establish trusted context.

### Sending CoSec Headers

| Header | Target |
|---|---|
| `CoSec-App-Id` | command header `app_id` |
| `CoSec-Device-Id` | command header `device_id` |
| `CoSec-Request-Id` | `CommandBuilder.requestIdIfAbsent` |
| `CoSec-Space-Id` | `CommandBuilder.spaceIdIfAbsent` and query-space fallback |

Existing standard Wow request/space values win through `IfAbsent` and rewrite precedence; CoSec headers only supplement them. Missing headers produce no context and do not fail by themselves.

### How the Context Flows

App/device values propagate in Wow message headers to downstream commands and events. Request ID and space ID enter command identity/scope. Query rewriting reads Wow space first, then falls back to `CoSec-Space-Id`. Treat propagated values as audit data or inputs to a verified policy, never as authorization merely because they came from headers.

Verified failures and boundaries: missing headers yield empty context; existing request/space values are not overwritten; query space creates only a `SpaceIdFilter`, not principal authorization; forged headers are faithfully propagated, so a missing security chain is a deployment failure rather than an input validator this module can add.

## Completion Gates

- Server policy rejects anonymous, forged-header, cross-tenant, cross-owner, and cross-space requests.
- Missing authorization labels fail closed instead of degrading to match-all.
- Downstream handlers distinguish propagated context from a trusted principal.
- Candidate-environment tests cover allowed, anonymous, forbidden, and cross-scope paths.
- The focused module check passes:

```bash
./gradlew :wow-cosec:check
```

Next, read [Data access](../data-access.md) to close authentication, authorization, filtering, and audit boundaries.
