---
name: b04-review-readonly
tags: [behavior, review, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

审查下游订单服务当前分支相对 origin/main 的完整 Wow 应用改动，只报告合并阻塞项；先解析 merge-base，如果没有阻塞项则明确写 `no blocking findings`，不要修改文件或远端状态。
