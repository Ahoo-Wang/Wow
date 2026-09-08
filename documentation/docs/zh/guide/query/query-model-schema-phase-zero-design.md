---
title: 查询 Schema 值模型
description: 当前查询架构摘要；保留原文档URL供已有链接使用。
---

# 查询 Schema 值模型

当前模型以递归 QueryValueSchema 保存 SCALAR、OBJECT、ARRAY、NULL、UNION 与 UNKNOWN，使用 properties/items/additionalProperties/alternatives 保留完整边界。按 typed path 保存的原生 bindings 与逻辑树分离。

本文保留原 URL，内容以当前实现为准，不再维护独立阶段设计。完整合同见[查询网关](./query-gateway.md)、[查询模型 Schema](./query-model-schema.md)和[查询后端](./query-backend.md)。性能结果需要实际运行证据，本页不宣称最终性能验收已完成。
