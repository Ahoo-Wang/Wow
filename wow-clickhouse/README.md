# Wow ClickHouse

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-clickhouse)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-clickhouse)

`wow-clickhouse` serves Wow's `DATASET` query model from ClickHouse: a declared dataset, such as an analyst's wide table, is queried, paged and aggregated through Wow's admission, capability descriptor, scope, policies and masking.

Spring Boot applications enable it through the starter's `clickhouse-support` capability and `wow.query.clickhouse.*`.

**The documentation is [Dataset Query](https://wow.ahoo.me/guide/query/dataset-query)** ([简体中文](https://wow.ahoo.me/zh/guide/query/dataset-query)): declaring datasets, the ClickHouse backend, the HTTP routes, governance and the semantic differences.
