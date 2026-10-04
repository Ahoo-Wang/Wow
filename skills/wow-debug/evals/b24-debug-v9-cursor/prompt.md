---
name: b24-debug-v9-cursor
tags: [behavior, debug, read-only, v9, cursor, root-cause, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游 Wow V9 服务通过 SnapshotQueryGateway 查询：第一页正常；应用收到 nextCursor 后自行 Base64 解码并重编码，同时把 sort 从 updateTime DESC 改为 createTime DESC，第二页报 Invalid cursor。只读复现并定位根因，不修改文件。
