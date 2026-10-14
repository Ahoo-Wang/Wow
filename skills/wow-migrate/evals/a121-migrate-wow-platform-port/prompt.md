---
name: a121-migrate-wow-platform-port
tags: [activation, trigger, migration, v9-to-v10, wow-platform-port]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

在 Wow 仓库的 `v10` 分支上，把 9.x 的 dataset-registry 领域（`@AggregateOwner`、`@CommandRoute`、`CommandMessage` 处理函数）移植为 `platform/dataset-registry/` 下的 V10 项目，先列出逐项改写。
