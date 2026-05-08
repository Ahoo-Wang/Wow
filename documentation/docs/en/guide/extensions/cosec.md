---
title: CoSec
description: CoSec security framework integration for Wow, handling security context propagation in command and query endpoints.
---

# CoSec

The CoSec extension integrates the [CoSec](https://github.com/Ahoo-Wang/CoSec) security framework with Wow's WebFlux command and query endpoints, handling security context injection and propagation.

## How It Works

CoSec integration provides three key components:

1. **CommandRequestHeaderAppender** — Extracts `CoSec-App-Id` and `CoSec-Device-Id` from HTTP request headers and appends them to command headers
2. **CommandBuilderExtractor** — Extracts `CoSec-Request-Id` and `CoSec-Space-Id` from HTTP request headers and injects them into the CommandBuilder
3. **MessagePropagator** — Propagates `app_id` and `device_id` from upstream message headers to downstream messages in the processing chain

## Installation

Add the `wow-cosec` dependency and enable the `cosec-support` capability in your Spring Boot Starter:

=== "Gradle (Kotlin)"

```kotlin
implementation("me.ahoo.wow:wow-spring-boot-starter") {
    capabilities { requireCapability("cosec-support") }
}
```

## Auto-Configuration

When both `wow-cosec` and CoSec are on the classpath, the `CoSecAutoConfiguration` automatically registers the security integration beans. No additional configuration is required.
