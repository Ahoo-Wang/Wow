---
title: OpenAPI 与 springdoc
description: 服务路由的 OpenAPI 3.1 文档：运行时提供、构建时生成，并与应用自己的控制器一起合并进 springdoc 的文档和 Swagger UI。
outline: deep
---

# OpenAPI 与 springdoc

服务的路由来自它的 Manifest，它们的 OpenAPI 文档也是：每个命令、状态读取、查询和数据集路由，以及 Manifest 中类型的请求和响应 schema。本页说明在哪里获取这份文档，以及它怎样并入 springdoc 的文档。

## 获取文档 {#get-the-document}

| 在哪里 | 何时 |
| --- | --- |
| `GET /wow/openapi` | 运行时，从服务合并后的 Manifest 生成（`wow.http.openapi.enabled`，默认打开） |
| `build/wow/openapi.json` | 构建时，由 Gradle 插件的 `generateWowOpenApi` 生成，供需要在服务运行之前就拿到它的工具使用 |

文档是 OpenAPI 3.1，使用 JSON Schema 2020-12 的 schema。它只描述公开端口：内部端口的调用、集群通道和信号从不出现在其中。

## 配合 springdoc {#with-springdoc}

应用的类路径上有 springdoc 时，Wow 的文档会并入 springdoc 的文档，所以 `/v3/api-docs` 和 Swagger UI 会同时列出应用自己的控制器和 Wow 的路由：

- Wow 的路由合并进 springdoc 的默认文档。两者都定义了的路由（按路径和方法），或者两者以不同定义使用的同一个组件名，会让启动失败；启动时会渲染一次文档来检查。
- 分组 `wow.http.openapi.springdoc.group`（`wow`）单独提供 Wow 的路由，位于 `/v3/api-docs/wow`。
- Swagger UI 先列出默认文档，再列出各个分组。
- `/v3/api-docs` 和 Swagger UI 属于 springdoc，由应用的安全配置保护。`GET /wow/openapi` 不变。

`wow.http.openapi.springdoc.enabled: false` 关闭这个桥接。

## 安全方案 {#security-schemes}

文档声明调用方怎样出示它的身份，这样 Swagger UI 的 “Try it out” 就会发送它。宿主根据它的调用方解析器填写这部分：CoSec 的解析器接收 bearer 令牌。使用自己的解析器的应用，用一个 `WowOpenApiSecurity` Bean 声明它的方案，这个 Bean 会替换宿主的。

## 基于文档的客户端 {#clients-from-the-document}

TypeScript 客户端从 Manifest 而不是 OpenAPI 文档生成，这样它们能保留 OpenAPI 表达不了的东西，例如等待计划和查询字段。OpenAPI 文档服务于其他所有语言和工具。
