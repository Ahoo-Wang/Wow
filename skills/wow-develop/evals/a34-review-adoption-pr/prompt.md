---
name: a34-review-adoption-pr
tags: [activation, trigger, adoption, review, conflict]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

审查这个 PR：它在我们现有的 Spring Boot 订单服务中第一次引入 Wow 的 Order 聚合与 Saga，重点看建模和事件语义是否正确，不要修改文件。
