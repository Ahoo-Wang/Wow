---
name: b136-data-query-busy
tags: [behavior, refusal, backpressure]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Count the dev Wow order service's PAID orders from yesterday in Asia/Shanghai. The first try answered 429 with code wow.query.busy and Retry-After: 2.
