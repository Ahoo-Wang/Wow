---
name: b45-client-filter-dsl
tags: [behavior, query-dsl, paging]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

Page PAID orders created yesterday in the Asia/Shanghai zone, 20 per page, newest first, returning only aggregateId and state.total, with the generated OrderSnapshotQueryClient of a Wow V10 service. Give the code and the commands in your answer.
