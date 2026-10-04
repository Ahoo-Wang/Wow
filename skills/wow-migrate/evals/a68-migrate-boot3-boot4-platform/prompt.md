---
name: a68-migrate-boot3-boot4-platform
tags: [activation, trigger, migration, spring-boot, platform-contract, configuration]
runs: 3
max_turns: 2
allowed_tools: [Read, Glob, Grep, Skill]
---

下游服务使用 Wow，计划从 Spring Boot 3.5 升级到 4.1；需要审计 Wow starter/capability、配置属性、Jackson、运行时自动配置和兼容性。
