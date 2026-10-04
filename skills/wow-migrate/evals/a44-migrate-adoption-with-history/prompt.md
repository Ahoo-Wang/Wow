---
name: a44-migrate-adoption-with-history
tags: [activation, trigger, adoption, data-cutover, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

将现有 Axon 订单服务迁移到 Wow 8.10，并转换历史事件、全量对账、切流和准备有目标写入时的回滚。
