---
title: 查询 Schema 原生绑定
description: 当前查询架构摘要；保留原文档URL供已有链接使用。
---

# 查询 Schema 原生绑定

QueryModelSchema 复用共享逻辑值树，按 Property/Item/Key 模板绑定每种原生能力。native binding提供绝对路径，Mongo元素谓词显式计算相对路径。未知字段、缺失能力与不完整作用域不会回退到调用者物理字段名。

本文保留原 URL，内容以当前实现为准，不再维护独立阶段设计。完整合同见[查询网关](./query-gateway.md)、[查询模型 Schema](./query-model-schema.md)和[查询后端](./query-backend.md)。性能结果需要实际运行证据，本页不宣称最终性能验收已完成。
