---
name: b31-review-query-policy-boundaries
tags: [behavior, review, query-policy, webflux, compatibility, read-only, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读审查下游设计，Wow 源码目标为 b9e43876a19655af36ba62f1acf7779175a25a13：应用在 QueryFilter.prepare 加强制 owner 条件，后续 prepare 会 withFilter(MatchAllFilter)；应用只提交 Gateway mock 单测，却宣称自定义 QueryPolicy Bean→HTTP→Backend 接入已完全验证。审查者还要求给 DefaultEventStreamQueryGateway 补旧二进制构造器，而任务明确只守护 QueryGateway API、实现 SPI 可破坏。请给出实际风险和必要证据，不实现。
