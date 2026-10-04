---
name: a72-migrate-v9-cursor
tags: [activation, trigger]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

将下游服务从 Wow 8.16.3 升级到当前 V9，并评估把 QueryService/PagedQuery 改为 QueryGateway/CursorQuery 的源码、HTTP 与数据边界。
