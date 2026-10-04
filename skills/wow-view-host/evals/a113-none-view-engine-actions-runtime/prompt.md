---
name: a113-none-view-engine-actions-runtime
tags: [activation, negative, wow-repository, engine]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

修改 typescript/wow-view-engine 的 runtime/actions.ts，让批量执行的并发从 4 改成可配置，并补单元测试。
