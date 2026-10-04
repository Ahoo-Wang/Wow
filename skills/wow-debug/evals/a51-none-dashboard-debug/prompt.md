---
name: a51-none-dashboard-debug
tags: [activation, negative, wow-repository, dashboard, debug]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

定位 compensation dashboard 的 Vitest hang，检查 React 状态与 fake timer，不修改 JVM 模块。
