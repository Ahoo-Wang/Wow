---
name: b45-client-filter-dsl
tags: [behavior, query-dsl, paging]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Page PAID orders whose createdAt is yesterday in the Asia/Shanghai zone, 20 per page, newest first, returning only state objects.
