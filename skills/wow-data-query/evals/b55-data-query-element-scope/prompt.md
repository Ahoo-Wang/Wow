---
name: b55-data-query-element-scope
tags: [behavior, event-stream, element-scope]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

在 dev 的购物车事件历史里，统计 CartItemAdded 事件中 quantity 大于 5 的事件流数量。
