---
name: b10-migrate-data-rehearsal
tags: [behavior, migration, data-cutover, planning, read-only]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

订单服务的源码已经从 Wow 9.x 迁到 V10（9.x 用 MongoDB 事件存储，V10 用 PostgreSQL），迁移时写好了 migration-map.json。请设计用 wow-migrate-data 做的一次隔离演练，以及生产切换前的数据门禁。现在只有源码和映射表，没有生产数据读取、写入、切流或发布授权。在回答里给出代码和命令。
