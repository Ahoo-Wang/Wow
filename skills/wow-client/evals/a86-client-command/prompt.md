---
name: a86-client-command
tags: [activation, trigger, command]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

在 TypeScript 前端里用 @ahoo-wang/wow-client 给购物车发送 add_cart_item 命令，并等到 SNAPSHOT 阶段再刷新列表。
