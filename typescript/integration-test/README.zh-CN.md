# 集成测试

这个私有包拿 Wow TypeScript 客户端和 `wow-generator` 生成的代码，对着运行中的 Wow
示例服务端做检查。它与各个包确定性的单元测试分开。

## 测什么

- 通过 `@ahoo-wang/wow-client` 发命令、查快照、加载状态、订阅事件流（`test/wow/cart/`）。
- `src/generated/` 下的生成代码：用 `tsc` 检查，并由 `generatedCartCommandClient.test.ts` 调用。
- `test/wow/wowOpenApi.test.ts`：`wow-client` 发给服务端的每个枚举值都要出现在服务端的
  OpenAPI 文档里。
- `@ahoo-wang/wow-react` 的 hook（`test/wow/react/`，在 jsdom 下运行）：按快照 URL
  查询的 hook、列表流 hook 的事件流及其 `WowError`，以及把生成的查询客户端作为
  `execute` 的 hook。
- `@ahoo-wang/wow-view-engine` 的运行时（`test/view-engine/`）：数据源就是 Wow 的
  `SnapshotQueryClient` 本身的 `ViewEngine`，查询套件用命令写入的销售订单。见
  [视图引擎对真服务端](#视图引擎对真服务端)。

## 前置条件

8080 端口上有一个连着 MongoDB 的 Wow 示例服务端。换地址时由 `WOW_EXAMPLE_SERVER_URL`
指定（例如 `WOW_EXAMPLE_SERVER_URL=http://localhost:18080/`）。可以从本仓构建服务端：

```bash
./gradlew :example-server:installDist
```

也可以运行已发布的镜像 `ghcr.io/ahoo-wang/wow-example-server:<version>`。用
`SPRING_MONGODB_URI` 指向 MongoDB，用 `SERVER_PORT` 指定端口，并设置
`WOW_EVENTSOURCING_STORE_STORAGE=mongo`、`WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo`。

## 生成与测试

在仓库根目录执行：

```bash
pnpm install --frozen-lockfile
pnpm --filter wow-integration-test... build
pnpm --filter wow-integration-test generate
pnpm --filter wow-integration-test typecheck
pnpm --filter wow-integration-test test
```

`generate` 读取 `http://localhost:8080/v3/api-docs` 并替换 `src/generated`。提交前先检查生成结果的改动。

`test` 在第一个用例之前等服务端就绪（`test/globalSetup.ts`）：先等 `/actuator/health` 答 `UP`，最多五分钟；再给套件用到的每个聚合各发一条命令，等到快照写入。刚启动的服务端在这里付掉头几条命令的代价，所以没有哪个用例需要为冷启动放宽超时。

`src/generated` 逐字节保存生成器的输出，连同它的清单 `.wow-generator.json`；ESLint 和
Prettier 都跳过这个目录。不要手改，也不要重新格式化。

## 视图引擎对真服务端

`test/view-engine/` 通过引擎的公开运行时（`new ViewEngine`、`create`、`open`、`edit`、
`apply`、`page`、`exportRows`，以及仪表盘的 `setFilterValue`、`setFilters`、`clearFilters`、
`crossFilter`）查询示例服务端。每个文件在自己的租户里写入十三张销售订单（`salesOrders.ts`：
下单，再全额或部分付款），从服务端读回它们的创建时间；每个断言都拿这些订单自己算出的数去比，
不拿引擎自己的输出做快照。

| 文件                 | 检查什么                                                                                                                                                                                                |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `recordView.test.ts` | 文本、枚举、数值区间、日期区间条件；两个字段的排序；三页；合计行；按五行一页分页的 CSV 导出；搜索——MongoDB 后端没有全文能力而拒绝，视图如实报出                                                         |
| `analysis.test.ts`   | 按字段、按展开的数组、按日／周／月分组；`COUNT`、`SUM`、`AVG`、`DISTINCT_COUNT`、`PERCENTILE` 与派生指标；「只保留」；「前 N 组」与探针行；拆分并「其他」；饼图的「其他」；按秒补齐；本月至今对上月同期 |
| `dashboard.test.ts`  | 仪表盘筛选接线到分析、指标卡和一个保存的记录视图；从一个面板交叉筛选；仪表盘的固定范围；走势卡锚定到板上日期筛选所选的那一天（D39），以及它还做不到的「较前一日」（`it.fails`，预期失败）               |

单独运行时，按[前置条件](#前置条件)启动服务端。8080 端口被占用时：

```bash
docker run -d --name wow-it-mongo -p 27117:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=root mongo:8.0
./gradlew :example-server:installDist
cd example/example-server/build/install/example-server
mkdir -p logs data
SERVER_PORT=18080 \
SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration,org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration \
SPRING_MONGODB_URI='mongodb://root:root@localhost:27117/wow_example_db?authSource=admin' \
WOW_EVENTSOURCING_STORE_STORAGE=mongo WOW_EVENTSOURCING_SNAPSHOT_STORAGE=mongo \
bin/example-server
```

然后在仓库根目录：

```bash
pnpm --filter wow-integration-test... build
cd typescript/integration-test
WOW_EXAMPLE_SERVER_URL=http://localhost:18080/ pnpm exec vitest run --maxWorkers=2 test/view-engine
```

`mongo:8.3` 在 Linux 内核 6.19 及以上拒绝启动（SERVER-121912），Docker Desktop 可能就是这样的内核；
CI 的 `mongo:8.3.11` 服务不受影响，本地用 `mongo:8.0`，这些查询的回答相同。热服务端上整套约五秒，
其中两秒是让按秒补齐的订单相隔几秒的停顿。

## CI

`.github/workflows/typescript-contract.yml` 在 Kotlin 源码、示例、Gradle 构建、这几个包或 `wow-view-engine` 的源码有改动时，
对着同一提交构建出来的示例服务端跑上面这些步骤。重新生成后 `src/generated` 有任何变化就失败，
任一步失败都会上传服务端日志。改到 `wow-client`、`wow-generator` 或本包时，还会从
`wow-example-server` 镜像 8.10.8 和 8.11.5 生成代码并做类型检查。

`typecheck` 用 `tsconfig.test.json` 把 `src` 和 `test` 一起做类型检查，不需要服务端。`typescript.yml` 的
Quality 作业经由根目录的 `pnpm typecheck` 在每个改到 TypeScript 的拉取请求上运行它，所以
`wow-client` 或 `wow-react` 的类型变化弄坏这里的用例时，不必等契约作业就能发现。

## 排查失败

1. 先构建；工作区导入解析不到，通常是包的产物过期或缺失。
2. 生成或测试前先访问 `http://localhost:8080/actuator/health`。
3. 服务端契约变了就重新生成。

[English](./README.md)
