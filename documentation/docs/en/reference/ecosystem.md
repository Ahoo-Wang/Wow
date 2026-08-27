---
title: Ecosystem and Resources
description: Verify ownership, in-repository integration boundaries, and installation entry points for Wow and related projects.
---

# Ecosystem and Resources

This page answers: **does Wow, an external project, or the downstream application own this capability?**

The repository links below were checked for availability. External projects own their releases, compatibility, and usage documentation; Wow owns only this repository's dependency selection, adapter modules, and examples. Pinned versions in `gradle/libs.versions.toml` describe this checkout's resolution and are not a cross-version compatibility or support promise.

## Ownership and usage boundary

| Project | Verified relationship in this repository | Installation and usage boundary | Authoritative entry |
|---|---|---|---|
| Wow | Owns framework APIs, runtime, Spring, and infrastructure adapters | Downstream applications select required capabilities through the Wow BOM, modules, or Starter capabilities | [Wow](https://github.com/Ahoo-Wang/Wow) |
| wow-project-template | Provides an independently evolving first-success project | Create from the template or clone it; verify its actual Wow version instead of assuming it matches this site | [wow-project-template](https://github.com/Ahoo-Wang/wow-project-template) |
| CosId | `wow-core` directly uses `cosid-core`; sample servers may add CosId Starter and stores | Wow supplies ID abstractions and default factories; applications/platforms own machine-ID allocation and production configuration | [CosId](https://github.com/Ahoo-Wang/CosId) |
| CoCache | `wow-cocache` adapts CoCache and also uses Wow API-client/query boundaries | Add `wow-cocache` only for that integration; cache consistency and backend operations remain application responsibilities | [CoCache](https://github.com/Ahoo-Wang/CoCache) |
| CoSec | `wow-cosec` and `cosec-support` adapt request-context propagation and query rewriting | Selecting the adapter does not complete authentication, authorization, or tenant isolation; verify the application's real security chain | [CoSec](https://github.com/Ahoo-Wang/CoSec) |
| CoApi | `wow-apiclient` uses `coapi-api`; sample servers use CoApi Starter to materialize clients | Wow defines generic client contracts; downstream configuration owns discovery, base URLs, authentication, and retry | [CoApi](https://github.com/Ahoo-Wang/CoApi) |
| Simba | The compensation server uses Redis-backed Simba for scheduler exclusion | An ordinary Wow application does not need to install Simba merely because it uses saga or compensation APIs | [Simba](https://github.com/Ahoo-Wang/Simba) |
| FluentAssert | The `wow-test` stack follows the FluentAssert `.assert()` convention | Applications get the Wow test DSL through `wow-test`; FluentAssert owns its complete assertion API | [FluentAssert](https://github.com/Ahoo-Wang/FluentAssert) |
| Fetcher | The compensation dashboard uses Fetcher packages and generated clients | This is a dashboard/TypeScript-client boundary, not a required JVM runtime dependency; update generated files through OpenAPI/generator input | [Fetcher](https://github.com/Ahoo-Wang/Fetcher) |

Use [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/Wow/blob/main/gradle/libs.versions.toml) and [`wow-dependencies`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-dependencies/build.gradle.kts) for current dependency and BOM facts. Use [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts) for module existence.

## How to choose

1. Start with [Module Dependencies](../guide/advanced/module-dependencies.md) to select the Wow module or Starter capability.
2. Read the matching extension page, such as [CoCache](../guide/extensions/cocache.md), [CoSec](../guide/extensions/cosec.md), or [API Client](../guide/extensions/apiclient.md).
3. Go to the external project only when that extension confirms the component is required.
4. On upgrade, re-verify resolved dependencies, compilation, the real backend integration, and runtime path. Shared authorship, one BOM, or a current example does not establish future compatibility.

## Content owned elsewhere

- Wow concepts and adoption cost: [Introduction](../guide/introduction.md)
- Exact configuration keys and defaults: [Core Configuration Reference](./config/core.md)
- Production backend and recovery ownership: [Best Practices](../guide/best-practices.md)
- Agent workflows and distribution: [Agent Skills](../guide/skills.md)
