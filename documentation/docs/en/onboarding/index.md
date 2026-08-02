---
title: Wow Onboarding
description: Choose an audience-specific path for contributing to, evaluating, or adopting Wow
---

# Wow onboarding

Welcome to Wow.

This onboarding hub routes each reader to the guide that matches the decisions they need to make.

Wow is a reactive domain-driven design framework built around CQRS and event sourcing, as summarized by the [project overview](https://github.com/Ahoo-Wang/Wow/blob/main/README.md#L51-L84).
Its current baseline is Wow `8.9.8`, Kotlin `2.4.10`, Spring Boot `4.1.0`, Gradle `9.6.1`, and Java `17`.
Those versions are defined by the repository rather than by this page: [project version](https://github.com/Ahoo-Wang/Wow/blob/main/gradle.properties#L18-L23), [dependency versions](https://github.com/Ahoo-Wang/Wow/blob/main/gradle/libs.versions.toml#L1-L35), [Gradle wrapper](https://github.com/Ahoo-Wang/Wow/blob/main/gradle/wrapper/gradle-wrapper.properties#L1-L9), and [JVM toolchain](https://github.com/Ahoo-Wang/Wow/blob/main/build.gradle.kts#L175-L190).

## Choose your path

| Audience | Start here | What you will learn | Suggested time |
| --- | --- | --- | --- |
| Contributor | [Contributor Guide](./contributor-guide.md) | Set up the repository, understand the runtime, implement a vertical slice, test it, and prepare a reviewable change. | About 60 minutes |
| Staff engineer | [Staff Engineer Guide](./staff-engineer-guide.md) | Reason about module boundaries, extension contracts, runtime invariants, migrations, and architecture decisions. | About 45 minutes |
| Executive | [Executive Guide](./executive-guide.md) | Understand the product shape, engineering model, strategic strengths, dependencies, and delivery risks. | About 30 minutes |
| Product manager | [Product Manager Guide](./product-manager-guide.md) | Translate domain behavior into commands, events, acceptance criteria, observability, and release scope. | About 30 minutes |

## Recommended reading order

New code contributors should begin with the [Contributor Guide](./contributor-guide.md).

Architecture owners can read the contributor guide first, then continue with the [Staff Engineer Guide](./staff-engineer-guide.md).

Product and leadership readers can start directly with their audience guide and return to the contributor guide when they need implementation detail.

## Source-of-truth rule

These guides explain the repository; they do not replace it.

When prose and code differ, follow the checked-in Gradle configuration, public contracts, implementation, tests, and CI workflows.
The [module list](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts#L23-L85), [test task wiring](https://github.com/Ahoo-Wang/Wow/blob/main/build.gradle.kts#L54-L142), [local-test workflow](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/local-test.yml#L14-L70), and [integration-test workflow](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/integration-test.yml#L14-L77) are the authoritative starting points.

## What these guides intentionally avoid

They do not promise latency, throughput, availability, retention, or compliance properties that are not enforced by code and operating configuration.

They also do not describe KSP as an HTTP route generator.
In this repository KSP is part of the compiler and metadata pipeline, while runtime WebFlux routing and OpenAPI support remain explicit modules and auto-configurations: [example KSP configuration](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/build.gradle.kts#L1-L8), [metadata processor output](https://github.com/Ahoo-Wang/Wow/blob/main/wow-compiler/src/main/kotlin/me/ahoo/wow/compiler/metadata/MetadataSymbolProcessor.kt#L61-L104), [WebFlux handler](https://github.com/Ahoo-Wang/Wow/blob/main/wow-webflux/src/main/kotlin/me/ahoo/wow/webflux/route/command/CommandHandlerFunction.kt#L43-L66), and [starter feature variants](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/build.gradle.kts#L5-L44).

## Useful entry points

- [Repository README](https://github.com/Ahoo-Wang/Wow/blob/main/README.md#L41-L84)
- [Module declarations](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts#L23-L85)
- [Cart example API](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-api/src/main/kotlin/me/ahoo/wow/example/api/cart/AddCartItem.kt#L1-L26)
- [Cart aggregate](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/cart/Cart.kt#L32-L76)
- [Cart specification](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/test/kotlin/me/ahoo/wow/example/domain/cart/CartSpec.kt#L28-L87)
- [Runtime test guide](../guide/test-runtime.md)
