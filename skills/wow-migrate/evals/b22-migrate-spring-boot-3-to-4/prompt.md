---
name: b22-migrate-spring-boot-3-to-4
tags: [behavior, migration, read-only, spring-boot, jackson, configuration, runtime-classpath, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划下游服务从 Wow 6.20.16 / Spring Boot 3.5.13 升级到固定 Wow 8.15.0 / Boot 4.1.1。应用使用 Mongo、Kafka、WebFlux、OpenAPI，远程 CoSky 仍配置 spring.data.mongodb.uri；Jackson 配置启用大小写不敏感 enum 及日期/时长 timestamp，自建 Jackson 2 ObjectMapper，并有自定义 Boot auto-configuration/exclusions。当前无远程配置、外部调用、数据写入或生产权限。
