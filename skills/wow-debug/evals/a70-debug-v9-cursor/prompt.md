---
name: a70-debug-v9-cursor
tags: [activation, trigger]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

下游 Wow V9 服务的 Snapshot cursor 第一页正常，回传 nextCursor 后第二页报 Invalid cursor；只复现并定位首个失败阶段。
