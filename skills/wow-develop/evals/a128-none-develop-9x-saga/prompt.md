---
name: a128-none-develop-9x-saga
tags: [activation, negative, version, 9x]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Add a saga to our order service that reserves stock when an order is created. It builds on `me.ahoo.wow:wow-spring-boot-starter:9.4.2` and its processors use `@StatelessSaga` and `@OnEvent`.
