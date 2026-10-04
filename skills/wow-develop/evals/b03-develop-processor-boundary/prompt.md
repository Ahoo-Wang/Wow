---
name: b03-develop-processor-boundary
tags: [behavior, projection, evidence-boundary, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读评估下游应用的 OrderProjector 若要增加重试和幂等保障，责任边界、实现入口和最低测试证据分别是什么；不要修改文件。
