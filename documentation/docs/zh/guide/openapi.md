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

文档是 OpenAPI 3.1，使用 JSON Schema 2020-12 的 schema。它不声明 `jsonSchemaDialect`：OpenAPI 3.1 的默认方言就是 JSON Schema 2020-12 再加上 OpenAPI 自己的关键字，schema 在它之下含义不变，而 Swagger UI 不接受其他方言。它只描述公开端口：内部端口的调用、集群通道和信号从不出现在其中；公开端口不提供的内部命令（`@InternalCommand`）及只有它们用到的 schema 也不在其中（[只在内部受理的命令](./aggregates#keep-a-command-internal)）。

请求按服务读取的内容描述。计算属性，即背后没有字段的 getter（`val searchCode: String get() = ...`），在 schema 中是 `readOnly` 且必填，因为服务写出的每个值都含有它，但服务不读入任何值。所以请求携带的每个类型（命令的命令体、生命周期命令的命令体、服务路由的请求或参数）若在任何深度含有计算属性，旁边就有第二个组件，即它的输入，名为 `<组件名>Input`（`me.ahoo.shop.ProductRefInput`）。输入是该类型的 schema：每个计算属性不再列于 `required`，其 schema 和 `readOnly` 保留，指向有输入的类型的每个引用都改为指向那个输入：请求可以省略该属性，也可以把从服务读到的、含有它的值交回。请求引用输入；响应、事件和状态引用类型本身。若 Manifest 已把这个名字给了自己的某个类型，该类型先取得名字，输入取第一个空闲的 `_<n>` 后缀（`me.ahoo.shop.ProductRefInput_2`）。TypeScript 客户端为同样的类型声明输入（`ProductRefInput`，冲突时编号为 `ProductRefInput2`）。

## 配合 springdoc {#with-springdoc}

应用的类路径上有 springdoc 时，Wow 的文档会并入 springdoc 的文档，所以 `/v3/api-docs` 和 Swagger UI 会同时列出应用自己的控制器和 Wow 的路由：

- Wow 的路由合并进 springdoc 的默认文档。两者都定义了的路由（按路径和方法），或者两者以不同定义使用的同一个组件名，会让启动失败；启动时会渲染一次文档来检查。
- 分组 `wow.http.openapi.springdoc.group`（`wow`）单独提供 Wow 的路由，位于 `/v3/api-docs/wow`。
- Swagger UI 先列出默认文档，再列出各个分组。
- `/v3/api-docs` 和 Swagger UI 属于 springdoc，由应用的安全配置保护。`GET /wow/openapi` 不变。

`wow.http.openapi.springdoc.enabled: false` 关闭这个桥接。

如果代理把服务挂在一个路径前缀下、转发时去掉这个前缀，并用 `X-Forwarded-Prefix` 送出它，Spring Boot 4.2 默认忽略这个头，除非设置 `server.request.header.forwarded.use-prefix: true`：不设置时，Swagger UI 的 URL 缺少这个前缀。按上下文前缀路由并保留前缀的网关（Wow 的路由正是这样设计的）无需设置。

## 安全方案 {#security-schemes}

springdoc 的文档（`/v3/api-docs`、`/v3/api-docs/wow`）声明调用方怎样出示它的身份，这样 Swagger UI 的 “Try it out” 就会发送它；`GET /wow/openapi` 和构建产出的文档不声明。宿主根据它的调用方解析器填写这部分：CoSec 的解析器接收 bearer 令牌，开发调用方接收 `Wow-*` 身份请求头，每个请求头一个方案。它们的全局 `security` 是一条列出它们全部的要求：它们是同一个身份的各个部分，由请求一起发送，而不是几选一。使用自己的解析器的应用，用一个 `WowOpenApiSecurity` Bean 声明它的方案，这个 Bean 会替换宿主的。

## 宿主添加的内容 {#what-the-host-adds}

运行中的服务提供的文档（`GET /wow/openapi`，以及 springdoc 的）还会列出宿主给路由添加的、Manifest 里没有的内容。构建生成的 `openapi.json` 不包含这些：构建写出它时没有配置宿主。

- **请求事实：** `wow.http.request-facts` 列出的请求头，每个都是每条命令的请求头参数，命令会把它拷进自己的头部。
- **请求 id 头：** 集成在 `Wow-Request-Id` 之外读取的请求头，是每条命令的参数；开着 CoSec 时是 `CoSec-Request-Id`。
- **过滤器在路由之前的回答：** 开着 CoSec 时，它的 429 `wow.request.rate-limited` 和 503 `wow.auth.unavailable`，列在每个操作上。

## 基于文档的客户端 {#clients-from-the-document}

TypeScript 客户端从 Manifest 而不是 OpenAPI 文档生成，这样它们能保留 OpenAPI 表达不了的东西，例如等待计划和查询字段；与文档一样，它们不含内部命令。OpenAPI 文档服务于其他所有语言和工具。
