---
name: b02-develop-test-first
tags: [behavior, aggregate, test-first, mutating, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

为下游应用的 Cart 聚合增加最多 10 种不同商品的约束：达到容量后仍允许增加已有商品数量，但拒绝第 11 种新商品；先补失败行为测试，再完成实现和聚焦验证。请将两个场景分别命名为 `Existing product at capacity` 与 `11th product rejected`。
