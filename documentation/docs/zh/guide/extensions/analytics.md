---
title: 分析服务
description: 开箱即用的分析服务 wow-analytics-server：ClickHouse 上的数据集、数据集注册表、分析视图与看板和它的控制台，接上企业自己的 ClickHouse 与 CoSec 即可使用，不必另部署 BI 平台。
---

# 分析服务

`wow-analytics-server` 是数据集平台的部署物：它把[视图存储](./view-store.md)、数据集注册表和 ClickHouse 上的[数据集查询](../query/dataset-query.md)组合成一个服务，带一个控制台，放在 CoSec 之后。企业部署它，接上自己的 ClickHouse 与签发令牌的 CoSec，就有：

- **数据集目录**：分析师加工好的宽表经审核发布为数据集，读者在控制台里打开就能分析；
- **分析视图与看板**：视图引擎的工作台与看板，保存在服务自带的视图存储里；占比、累计、同比环比、组内排名由 ClickHouse 在全部分组上计算（[窗口](../query/aggregation-query.md#windows)）；
- **数据集的起草、审核与发布**：管理员从 ClickHouse 表起草声明，审核每个字段的敏感性，发布与下线。

## 组成

| 依赖                           | 作用                                                                  |
| ------------------------------ | --------------------------------------------------------------------- |
| `wow-view-store-starter`       | 视图、看板与偏好                                                      |
| `wow-dataset-registry-starter` | 数据集的草稿、审核、发布与目录                                        |
| `wow-clickhouse`               | ClickHouse 数据集查询与命名数据源（`wow.query.clickhouse.sources.*`） |
| `wow-mongo`                    | 视图存储与注册表两个聚合的事件与快照                                  |
| `wow-kafka`                    | 命令、事件与状态总线；单实例可改用内存总线                            |
| `wow-cosec`、CoSec 6.0         | 令牌校验、主体的租户与 ABAC 标签                                      |

注册表的三个模块 `wow-dataset-registry-api`、`-domain`、`-starter` 也发布到 Maven Central，可以嵌进任何需要它的业务服务。

## 安全

服务前面不需要网关，它自己就是这道门：

- **验签**：每个请求的 JWT 用 `cosec.jwt.secret`（发行配置读环境变量 `COSEC_JWT_SECRET`）校验，必须与企业 CoSec 签发令牌的密钥一致；
- **路由**：健康探针、控制台与 API 文档公开，其余一律要有效令牌（401）；注册表的命令要 `wow.analytics.admin-role`（缺省 `analytics-admin`，否则 403）；
- **视图存储**：照[视图存储的网关规则](./view-store.md#安全模型)执行——路径的租户是令牌的租户、所有者是令牌的 `sub`；共享视图本租户可读，写要 `wow.analytics.shared-view-role`；系统视图只给平台租户里有 `wow.analytics.system-view-role` 的主体写；`CoSec-App-Id` 由服务设成 `wow.analytics.app-id`；
- **数据集**：查询的租户取 CoSec 认证的租户，请求自报的不算；ABAC 标签取主体以 `abac.` 开头的属性（`abac.region = "east,north"`），没有标签的主体被拒绝（`wow.query.abac.require-principal-tags`）。见[用 CoSec 的主体属性作标签](../data-access.md#cosec-abac)。

## 运行

Docker 镜像 `ahoowang/wow-analytics-server`（也发布在 GHCR 与阿里云）：

```bash
docker run -p 8080:8080 -e COSEC_JWT_SECRET=<企业 CoSec 的签名密钥> -v $(pwd)/config:/opt/wow-analytics-server/config ahoowang/wow-analytics-server
```

挂进去的 `config/application.yaml` 写明 MongoDB、Redis（CoSid 的机器号）、Kafka 与 ClickHouse 的地址；从源码构建发行包：

```bash
pnpm --filter wow-analytics-console... build
```

```bash
./gradlew :wow-analytics-server:installDist
```

| 配置                             | 缺省                                          | 含义                               |
| -------------------------------- | --------------------------------------------- | ---------------------------------- |
| `wow.analytics.admin-role`       | `analytics-admin`                             | 能改数据集注册表的 CoSec 角色      |
| `wow.analytics.shared-view-role` | `analytics-admin`                             | 能写本租户共享视图的角色           |
| `wow.analytics.system-view-role` | `admin`                                       | 平台租户里能写系统视图的角色       |
| `wow.analytics.app-id`           | `analytics`                                   | 视图存储记下的应用                 |
| `wow.analytics.public-paths`     | 控制台静态文件、API 文档                      | 不要令牌的路径（健康探针总是公开） |
| `wow.analytics.console-paths`    | `/login`、`/datasets/**`、`/boards`、`/admin` | 答成控制台 `index.html` 的页面地址 |

## 控制台

控制台读 CoSec 前端共用的 `cosec-token`（企业的单点登录存了它就直接登录），没有时粘贴企业签发的访问令牌。首页列出已发布的数据集与看板；打开一个数据集就是在它的工作台里开始分析；看板的面板可以读任意数据集。管理员多一个「数据集管理」页：从表起草、修订声明（范围里的租户列 `scope.tenant` 与标签列 `scope.tags`）、逐字段审核敏感性、发布与下线。

## 演示

仓库里的 `analytics/demo` 在一台机器上把整条链路跑起来：ClickHouse 里一张零售分析宽表（两年的订单明细，按省、市、渠道、品类，原地生成），经注册表起草、修订、审核（会员手机号打码）并发布为数据集「零售订单明细」，再给租户共享四个分析——各省 GMV 前三的城市（组内排名）、月度 GMV 较去年同期、各渠道 GMV 占比、上月 GMV 指标卡（较去年同期）。不需要 CoSec、Kafka 与 Redis：脚本用演示密钥签发令牌。

先在仓库根目录构建控制台与发行包（镜像打包的就是它）：

```bash
pnpm --filter "wow-analytics-console..." build
```

```bash
./gradlew :wow-analytics-server:installDist
```

再启动演示并发布数据集：

```bash
docker compose -f analytics/demo/compose.yaml up -d --build
```

```bash
node analytics/demo/demo.mjs
```

打开 <http://localhost:8080/>，用脚本打印的令牌登录：`Analyst, every region` 看全部数据，`Analyst, the east alone` 只看华东的行（ABAC），`Admin` 还能进数据集管理页。集成测试 `RetailDemoTest` 用同一份 SQL、声明与审核跑通服务，并把每个分析与 ClickHouse 自己的 SQL 对数，演示坏了构建就会失败。
