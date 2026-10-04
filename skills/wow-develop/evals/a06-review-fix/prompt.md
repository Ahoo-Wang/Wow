---
name: a06-review-fix
tags: [activation, trigger, review-fix, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查并修复下游应用当前的 Wow PR；先完成 findings，再处理已授权问题并复审。
