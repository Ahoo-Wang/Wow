---
name: b134-data-query-dataset-unavailable
tags: [behavior, dataset, refusal]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

From the staging Wow analytics service, what was last month's GMV per city? Use the dataset analytics.order_wide. When I tried, it answered 409 with code wow.query.dataset-unavailable.
