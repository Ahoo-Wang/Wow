---
name: a52-migrate-wow-runtime-contract-break
tags: [activation, trigger, generated-contract, runtime-contract, conflict]
runs: 3
max_turns: 8
allowed_tools: [Read, Glob, Grep, Skill]
---

将 Wow 8.9 升级到 8.10：目标 tag 改变了 Wow 生成元数据和 runtimeClasspath 自动配置契约；无需数据转换，但要审计适配和回滚。
