---
name: a05-review-diff
tags: [activation, trigger, review]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游订单服务当前分支相对 origin/main 的 Wow aggregate 改动，按严重度报告 findings，不要修改文件。
