---
name: a87-client-filter
tags: [activation, trigger, query-dsl]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

Build a snapshot query with @ahoo-wang/wow-client that pages PAID orders created yesterday in Asia/Shanghai, sorted by createdAt descending.
