---
name: b34-develop-v10-new-service
tags: [behavior, v10, adoption, aggregate, test-first, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

We are starting a new service on Wow V10 (source target: the Wow `v10` branch, nothing released yet). The bounded context is the product catalog. Lay out the Gradle modules and their dependencies, write a `Product` aggregate whose `ChangePrice` command rejects a negative price with a domain error code, and give the aggregate test. Then say which context name you chose and why, which code the compiler generates for the aggregate, and which HTTP route the command gets. Do not modify files.
