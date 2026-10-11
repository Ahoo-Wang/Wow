# Wow ClickHouse

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-clickhouse)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-clickhouse)

`wow-clickhouse` 由 ClickHouse 提供 Wow 的 `DATASET` 查询模型：声明的数据集（例如分析师加工的宽表）经 Wow 的准入、能力描述、范围、策略与脱敏提供查询、分页与聚合。

Spring Boot 应用通过 starter 的 `clickhouse-support` 能力与 `wow.query.clickhouse.*` 开启它。

**文档见[数据集查询](https://wow.ahoo.me/zh/guide/query/dataset-query)**（[English](https://wow.ahoo.me/guide/query/dataset-query)）：声明数据集、ClickHouse 后端、HTTP 路由、治理与语义差异。
