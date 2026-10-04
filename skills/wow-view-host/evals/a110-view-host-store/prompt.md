---
name: a110-view-host-store
tags: [activation, trigger, storage]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

把 @ahoo-wang/wow-view-engine 的视图存储从 MemoryViewStore 换成 Wow 视图存储后端（@ahoo-wang/wow-view-store），我们前面有 CoSec 网关，共享视图只有运营主管能写。
