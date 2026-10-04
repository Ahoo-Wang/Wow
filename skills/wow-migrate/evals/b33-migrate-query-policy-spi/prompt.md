---
name: b33-migrate-query-policy-spi
tags: [behavior, migration, query-policy, read-only, source-contract, compatibility, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

下游从 Wow 9.0.10 迁到 commit b9e43876a19655af36ba62f1acf7779175a25a13（9.0.11 候选），自定义 AbstractQueryGateway、around QueryFilter、Schema Provider 和 Backend，并配置 validation-mode=strict。只读规划迁移：仅要求 QueryGateway 调用 API 和旧 Condition 约定保留，具体实现 SPI 可破坏。列出必要迁移、兼容范围及验证，不部署。
