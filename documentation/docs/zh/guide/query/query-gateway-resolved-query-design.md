---
title: 查询 Gateway 架构
description: 当前查询架构摘要；保留原文档URL供已有链接使用。
---

# 查询 Gateway 架构

当前实现采用固定 Gateway 执行顺序：一次 Schema 获取、请求 prepare、scope、QueryPolicy、默认条件、公共校验、Backend(query, schema)、Mask、typed 物化与终止观察。Snapshot 与 EventStream 共享策略执行链，具体 ABAC 标签策略仅适用 Snapshot。Schema 保存不可变事实，原生编译由 Backend 负责。

本文保留原 URL，内容以当前实现为准，不再维护独立阶段设计。完整合同见[查询网关](./query-gateway.md)、[查询模型 Schema](./query-model-schema.md)和[查询后端](./query-backend.md)。性能结果需要实际运行证据，本页不宣称最终性能验收已完成。
