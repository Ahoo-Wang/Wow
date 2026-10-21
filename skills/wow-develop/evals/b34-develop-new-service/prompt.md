---
name: b34-develop-new-service
tags: [behavior, adoption, aggregate, test-first, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

We are starting a new service on Wow V10 (the 10.0.0 pre-release line). The bounded context is the product catalog. Lay out the Gradle modules and their dependencies, write a `Product` aggregate whose `ChangePrice` command rejects a negative price with a domain error code, and give the aggregate test. Then say which context name you chose and why, which code the compiler generates for the aggregate, and which HTTP route the command gets. Do not modify files.
