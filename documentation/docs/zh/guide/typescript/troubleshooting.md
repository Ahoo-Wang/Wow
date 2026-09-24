---
title: TypeScript 客户端排障
description: 按报错信息找出 wow-client、wow-generator 与 wow-react 常见失败的原因。
---

# TypeScript 客户端排障

本页回答：**Wow TypeScript 包报的这个错是什么意思，怎样修？** 在本页搜索你看到的报错。服务端的故障——命令超时、版本冲突、投影——见服务端的[故障排查](../troubleshooting.md)。

大多数请求失败以 fetcher 的 `ExchangeError` 到达代码；它的 `message` 和 `cause` 说明了出了什么错，服务端有应答时 `await toWowError(error)` 能读出 Wow 的错误码。见[错误处理](./error-handling.md)。

## 安装

| 报错 | 原因 | 修复 |
|---|---|---|
| `404 Not Found - GET https://registry.npmjs.org/@ahoo-wang%2fwow-client`（`E404`） | 这些包随 Wow 9.2.0 发布 | 在此之前使用 5.x 的 `@ahoo-wang/fetcher-wow` 和 `@ahoo-wang/fetcher-generator`；见[发布状态](./compatibility.md#发布状态) |
| `WARN … unmet peer @ahoo-wang/wow-client@~9.2.0: found 9.3.0` | 应用里的 Wow 包处于不同的次版本 | 让 `wow-client`、`wow-generator` 和 `wow-react` 使用同一个版本 |
| `Cannot find module 'react/compiler-runtime'` | React 低于 19 | `wow-react` 需要 React 19.3 或更高；不支持 React 18 |
| `Unsupported engine … wanted: {"node":">=22.12.0"}` | Node.js 低于 22.12 | 升级 Node.js |

## 编译

| 报错 | 原因 | 修复 |
|---|---|---|
| `src/generated` 中出现 `TS1206: Decorators are not valid here`、`TS1241: Unable to resolve signature of method decorator` | 生成的客户端是旧式装饰器类 | 在编译它们的 `tsconfig.json` 中设置 `"experimentalDecorators": true` |
| `NodeNext` 下出现 `TS2307: Cannot find module './generated'` | Node 解析需要具体文件 | 导入 `./generated/index.js` |
| 过滤字段上出现 `TS2345`，例如 `'state.itmes'` | 查询客户端用聚合的字段名给字段定类型 | 改正字段名；生成的 `…AggregatedFields` 枚举列出了它们 |
| `TS2305: … has no exported member 'and'`（或 `eq`、`Operator`、`Condition`） | `Condition` API 在 `@ahoo-wang/wow-client/legacy` | 使用根入口的 `filter.*`；对接 Wow 8.10 服务端时从 `/legacy` 导入 |
| `TS2882: Cannot find module or type declarations for side-effect import of '…/styles.css'` | TypeScript 6 会检查副作用导入 | Vite 项目通过 `vite/client` 声明了 `*.css`；其他项目加上 `declare module '*.css';` |

## 发送请求

| 报错 | 原因 | 修复 |
|---|---|---|
| `Failed to parse URL from /example/owner/…` | 客户端没有 `fetcher`，默认 Fetcher 也没有基础地址 | 传入 `{ fetcher }`，或把服务注册为默认实例；见[认证与拦截器](./authentication.md#客户端使用哪个-fetcher) |
| `Request failed with status code 404 for http://…/example/owner/…` | 生成的客户端加了网关据以路由的限界上下文前缀（`example`），而请求直接发给了服务 | 命令客户端传 `basePath: ''`、查询客户端传 `contextAlias: ''`，或者改经网关发送 |
| 经网关时出现不带前缀的 `404` | 前缀被清掉了，或者网关按别的别名路由 | 保留生成的默认值，或者把 `basePath` / `contextAlias` 设为网关的路由 |
| `Missing required path parameter: ownerId`（或 `tenantId`），同时有 `[fetcher-decorator] Path template … has placeholder(s) {ownerId}` 警告 | 按所有者或租户划分的路由，没有东西填入它的变量 | 对 Fetcher 应用 CoSec 并登录，或者传 `urlParams: { path: { ownerId } }`；见[认证与拦截器](./authentication.md) |
| 浏览器中出现 `TypeError: Failed to fetch`，控制台有 CORS 提示 | 服务不允许页面所在的源 | 在服务或网关上配置 CORS，或让 API 与页面同源 |
| 每个请求都是 `401` | 没有令牌，或者 CoSec 没有应用到客户端所用的 Fetcher 上 | 检查客户端用的是哪个 Fetcher；见[认证与拦截器](./authentication.md) |

## 服务端应答

`toWowError(error)` 返回的错误码：

| `errorCode` | 状态 | 原因 | 修复 |
|---|---|---|---|
| `CommandValidation` | 400 | 命令体不满足校验规则 | 把 `bindingErrors` 显示在对应字段旁；见[错误处理](./error-handling.md#在表单上展示校验错误) |
| `IllegalArgument`、`IllegalState` | 400 | 命令处理函数拒绝了命令 | 显示 `errorMsg`；需要改的是请求本身 |
| `NotFound` | 404 | 没有该 id 的聚合或快照 | 处理不存在的情况；命令之后先等待 `CommandStage.SNAPSHOT` 再读取 |
| `RequestTimeout` | 408 | 等待的阶段没在 `timeoutMs` 内到达 | 服务端已收到命令，它仍可能完成；读取状态，不要重发 |
| `DuplicateRequestId` | 400 | 同一个请求 ID 发送了两次 | 第一次请求已经到达服务端；不要重发 |
| `EventVersionConflict`、`CommandExpectVersionConflict` | 409 | 另一次写入抢先了 | 重新加载状态后再决定 |
| `QuerySchemaValidation`：`Field [state.items.productId] requires its declared element scope` | 400 | 直接过滤了数组元素里的字段 | 用 `filter.elementMatch('state.items', filter.eq('productId', …))` 匹配 |
| 关于字段名的 `QuerySchemaValidation` | 400 | 该字段在这个聚合上不可查询 | 使用生成的字段名；服务变化后重新生成 |
| `QuerySchemaUnavailable`：`No query backend is configured for aggregate` | 503 | 服务没有供查询的快照存储（例如内存配置） | 配置服务的查询后端 |
| Wow 8.11 及以后的服务端对带 `raw()` 的 `/legacy` 查询应答 `400` | 400 | `raw()` 只对 Wow 8.10 有效 | 用 `filter.*` 改写查询 |

## 流

| 现象 | 原因 | 修复 |
|---|---|---|
| 对查询流的 `for await` 抛出 `WowError` | 服务端中途失败，以错误事件结束了流 | 像处理失败请求一样处理；见[错误处理](./error-handling.md#流) |
| 生成的 `…StreamCommandClient` 产出一个名为 `RequestTimeout`（或其他错误码）而不是阶段名的事件 | 生成的流客户端使用 Fetcher 普通的事件提取器 | 对照 `CommandStage` 检查 `event.event`，或者使用会抛出 `WowError` 的 `CommandClient.sendAndWaitStream` |
| 流式命令在 `PROCESSED` 结束，`errorCode` 不是 `Ok` | 命令处理函数失败 | 逐个检查结果的 `errorCode` |
| 组件已经卸载，连接仍然开着 | 流既没读完也没被中止 | 传入 `AbortSignal` 并在清理时中止，或 `break` 出循环 |

## 生成

| 退出码或报错 | 原因 | 修复 |
|---|---|---|
| 退出码 2：`Cannot read the OpenAPI document …: HTTP 401 Unauthorized` | 文档需要凭据 | 传入 `-H "Authorization: Bearer …"` |
| 退出码 2：`… no response within 30000 ms` | 服务不可达或太慢 | 检查 URL，或调大 `--timeout` |
| 退出码 2：`… is a Swagger 2.0 document` | `wow-generator` 只读 OpenAPI 3.x | 先转换，例如用 swagger2openapi |
| 退出码 3 | 配置文件读不到、解析不了或校验不通过，或者 `-c` 指定的文件不存在 | 修正报错里点名的文件；见[配置](../../reference/typescript/wow-generator/configuration.md) |
| 退出码 4 | 两个 schema 或两个方法生成了同一个名字、`$ref` 指向不存在的内容、Wow 元数据格式不对，或开启 `--strict` 且有警告 | 阅读报错；用配置中的 `methodNames` 为方法命名 |
| 缺了某个方法，并有警告点名它的操作 | 该操作没有 `operationId` 或没有 tag | 在服务里给它补上两者；`--strict` 会把这个警告变成退出码 4 |
| 警告 `… uses the deprecated name; rename it to wow-generator.config.json` | 读取的是 `fetcher-generator.config.json` | 改名为 `wow-generator.config.json` |
| 某个聚合没有生成命令客户端或查询工厂 | 文档没有把它描述成 Wow 聚合 | 见 [Wow 聚合识别](../../reference/typescript/wow-generator/wow-discovery.md) |

加 `--verbose` 运行可以看到每一步以及失败时的堆栈。

## 仍然没解决

收集失败请求的方法与 URL、状态码、`Wow-Error-Code` 响应头和响应体、包版本（`pnpm ls @ahoo-wang/wow-client @ahoo-wang/fetcher`）以及服务端的 Wow 版本，到 [Ahoo-Wang/Wow](https://github.com/Ahoo-Wang/Wow/issues) 提 issue。
