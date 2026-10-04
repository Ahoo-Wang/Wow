---
title: 视图存储
description: 视图引擎保存的视图与偏好在 Wow 服务端的落点：嵌入 wow-view-store-starter 或运行独立服务端与它的 Docker 镜像，配置项、MongoDB 与 Elasticsearch 上的存储、系统视图，以及由 CoSec 网关负责的安全模型。
---

# 视图存储

视图存储是视图引擎 `ViewStore` 端口在 Wow 上的服务端：读者保存的视图与偏好是两个 Wow 聚合，`view`（视图）与 `view_preferences`（视图偏好），限界上下文 `view-store`。前端用 `@ahoo-wang/wow-view-store` 的 `WowViewStore` 接它（[视图存在哪里](../typescript/view-engine-storage.md)）。

**它不做认证。** 租户、所有者与应用按请求写的取：路径里的 `{tenantId}`、`{ownerId}` 与请求头 `CoSec-App-Id`。谁能用哪条路径，全由前面的 CoSec 网关决定。所以每个部署——嵌入的也好，独立的也好——都只能放在 CoSec 网关后面（[安全模型](#安全模型)）。

## 模块

| 模块 | 内容 |
|---|---|
| `wow-view-store-api` | 限界上下文 `view-store`、命令、事件、`ViewKind`／`ViewAudience`、`SystemView`、错误码 |
| `wow-view-store-domain` | 聚合 `View`（`view`）与 `ViewPreferences`（`view_preferences`）及其规则 |
| `wow-view-store-starter` | Spring Boot 自动配置：把视图存储嵌进一个 Wow 服务 |
| `wow-view-store-server` | 基于 starter 的独立服务，事件与快照存在 MongoDB；以 Docker 镜像发布 |

前三个模块从 9.2.0 起发布到 Maven Central，由 `wow-bom` 约束版本，与视图引擎的 npm 包同一个版本号。

## 嵌入 starter，还是运行独立服务端

| | 嵌入 `wow-view-store-starter` | 独立的 `wow-view-store-server` |
|---|---|---|
| 适合 | 已经有一个 Wow WebFlux 服务，视图跟着这个业务走 | 没有自己的 Wow 服务，或想把各个前端的视图集中存放 |
| 存储 | 宿主用的存储：MongoDB 或 Elasticsearch | 只有 MongoDB（9.2.0） |
| 消息总线 | 宿主的；视图存储的 Kafka 主题可以单独加前缀 | 自己的 Kafka 主题前缀 |
| 例子 | 示例服务端（`example/example-server`）、补偿服务（补偿控制台的视图存在那里） | Docker 镜像 `wow-view-store-server` |

### 嵌入 starter

在一个 Wow WebFlux 服务里加上依赖：

```kotlin
implementation("me.ahoo.wow:wow-view-store-starter")
```

两个聚合随 domain 模块进到 classpath，Wow 像路由宿主自己的聚合一样路由它们，前缀是 `/view-store/tenant/{tenantId}/owner/{ownerId}/…`。starter 加上它们需要的其余部分，每个 Bean 都以视图存储命名、只碰视图存储的聚合与路径，宿主自己的聚合、路由与查询不受影响。

- **开放的路由**：`POST /view`（创建）、`PUT /view/{id}/save` 与 `/rename`、`DELETE /view/{id}`；`PUT /view/{id}/share`（在视图的个人路径上，移到 `owner/(shared)`）与 `PUT /view/{id}/claim`（在调用者自己的个人路径上，把共享视图收为己有）；`/view/snapshot/…` 与 `/view_preferences/snapshot/…` 的快照查询；`GET /system-views`、`GET`／`PUT /definitions/{definitionId}/preferences` 与 `GET /view/requests/{requestId}`（重放）。
- **其余路由一律 404**：Wow 为两个聚合生成的状态、追踪、事件流、快照维护、补偿、恢复与资源标签路由，以及它们命令的命令门面，都关掉了。少一条路由，网关就少一条要管的路径。
- 命令不带 id（Wow 从 `{id}` 取）；没有字段的命令（`share`、`delete`）以 `{}` 为请求体。
- 设 `wow.view-store.enabled=false` 关掉 starter。

**聚合名会撞。** 两个聚合叫 `view` 与 `view_preferences`，Wow 的 MongoDB 集合（`view_event_stream`、`view_snapshot`……）不带上下文名。宿主自己若有叫 `view` 或 `view_preferences` 的聚合，会共用这些集合：不要在它里面嵌入 starter，或者给宿主的聚合改名。

**路径大小写。** starter 的规则都不区分大小写地匹配路径，所以设了 `PathMatchConfigurer.setUseCaseSensitiveMatch(false)` 的宿主也受保护。只有一处例外：开放路由只在它自己的大小写下胜过关闭的路由，所以在不区分大小写的宿主上，`…/view/REQUESTS/state` 这样的路径答 404。用别的解析选项替换了 Spring 的 `RouterFunctionMapping` 的宿主，不要嵌入 starter。

### 独立服务端

`wow-view-store-server` 在与补偿服务相同的中间件上运行 starter，可以多实例部署：

| 中间件 | 用途 | 配置 |
|---|---|---|
| MongoDB | 事件流与快照（Wow 的默认存储） | `spring.mongodb.uri` |
| Kafka | 命令、事件与状态事件总线（Wow 的默认总线） | `wow.kafka.bootstrap-servers`、`wow.kafka.topic-prefix` |
| Redis | 多个实例共享的 CosId 机器号 | `spring.data.redis.url`、`cosid.machine.distributor.type: redis` |

**9.2.0 只支持 MongoDB。** 独立服务端构建时不带 Wow 的 Elasticsearch 支持。要把视图存在 Elasticsearch 上，把 starter 嵌进一个跑在 Elasticsearch 上的宿主：索引定义随 starter 发布，不随服务端。

从源码运行（仓库根目录下）：

```bash
service_dir=view-store/wow-view-store-server
mkdir -p "$service_dir/logs" "$service_dir/data" "$service_dir/config"
test -e "$service_dir/config/application.yaml" || cp "$service_dir/src/dist/config/application.yaml" "$service_dir/config/application.yaml"
./gradlew :wow-view-store-server:run
```

`src/dist/config/application.yaml` 是 `config/application.yaml` 的模板，所有后端都指向 `localhost`。

### Docker 镜像

镜像从 `installDist` 构建，有 `linux/amd64` 与 `linux/arm64` 两种架构，推到三处：

- `ahoowang/wow-view-store-server`
- `ghcr.io/ahoo-wang/wow-view-store-server`
- `registry.cn-shanghai.aliyuncs.com/ahoo/wow-view-store-server`

`v<version>` tag 发布 `<version>` 与 `<major>.<minor>`；推到 `main`（以及每天一次的构建）发布 `main`。预发布 tag（`v9.2.0-rc.0`）不发布镜像。老版本线上的补丁（`v9.3.0` 之后的 `v9.2.3`）不移动 `latest`，它停在最高的稳定版本上。

镜像以非 root 用户在 8080 端口运行 `/opt/wow-view-store-server/bin/wow-view-store-server`，从 `/opt/wow-view-store-server/config/` 读配置：把自己的 `application.yaml` 挂到那里，或者用环境变量覆盖。三个后端的地址都要设：漏了一个，容器照样启动，指向 `localhost`，第一次用到那个后端时才失败。

| 环境变量 | 配置项 | 模板里的值 |
|---|---|---|
| `SPRING_MONGODB_URI` | `spring.mongodb.uri` | `mongodb://root:root@localhost:27017/wow_view_store_db?authSource=admin&maxIdleTimeMS=60000` |
| `WOW_KAFKA_BOOTSTRAPSERVERS` | `wow.kafka.bootstrap-servers` | `PLAINTEXT://localhost:9092` |
| `WOW_KAFKA_TOPICPREFIX` | `wow.kafka.topic-prefix` | `wow.view-store-server.` |
| `SPRING_DATA_REDIS_URL` | `spring.data.redis.url`（CosId 机器号） | `redis://localhost:6379` |

```bash
docker run -d --network internal \
  -e SPRING_MONGODB_URI='mongodb://root:root@mongo:27017/wow_view_store_db?authSource=admin' \
  -e WOW_KAFKA_BOOTSTRAPSERVERS='PLAINTEXT://kafka:9092' \
  -e SPRING_DATA_REDIS_URL='redis://redis:6379' \
  ahoowang/wow-view-store-server:9.2.0
```

- 这里没有 `-p 8080:8080`：**镜像不做认证**，它的端口只给 CoSec 网关访问，绝不直接暴露给用户。`internal` 是网关与它共用的网络。
- `HEALTHCHECK` 读 `/actuator/health/liveness`（进程活着）。后端宕了显示在 `/actuator/health`，模板只答状态（`show-details: when-authorized`），端口不泄露后端的名字与地址。
- JVM 参数（`-Xms512M -Xmx512M`、ZGC、`logs/` 下的 GC 日志、`data/` 下的堆转储）来自 `installDist` 的启动脚本；要追加就设 `JAVA_OPTS`。

## 配置项

starter 的配置在 `wow.view-store` 下（`ViewStoreProperties`）：

| 配置项 | 缺省 | 作用 |
|---|---|---|
| `wow.view-store.enabled` | `true` | 是否把视图存储加进宿主 |
| `wow.view-store.system-views` | 空 | 配置的系统视图，见[系统视图](#系统视图) |
| `wow.view-store.kafka.topic-prefix` | 未设 | 只给视图存储两个聚合的 Kafka 主题前缀，见下文 |

相关的 Wow 配置：

| 配置项 | 与视图存储的关系 |
|---|---|
| `wow.kafka.topic-prefix` | 没设 `wow.view-store.kafka.topic-prefix` 时，视图存储的主题跟着它；独立服务端用它（`wow.view-store-server.`） |
| `wow.elasticsearch.index-prefix` | 视图存储的索引没有自己的前缀，跟着它移动（[多个部署共用一个集群](./elasticsearch.md#index-prefix)） |

### 一个 Kafka 主题命名空间只放一个部署

Wow 按上下文与聚合给 Kafka 主题命名（`wow.view-store.view.command`……），所以同一个 Kafka 集群上的两个视图存储部署（独立服务端与一个嵌入 starter 的宿主，或两个宿主）会消费对方的命令与事件。给每个部署一个自己的前缀：

- **嵌入 starter 的宿主**设 `wow.view-store.kafka.topic-prefix`，它只作用于视图存储的两个聚合（`<prefix>view-store.view.command`……），宿主自己的聚合仍用 `wow.kafka.topic-prefix` 给的主题。补偿服务设的是 `wow.compensation-service.`。不设或为空白时，视图存储的主题跟着 `wow.kafka.topic-prefix`。设了它以后，宿主的每个主题转换器 Bean 都必须只是一种（命令、事件流或状态事件），和 Wow 自己的一样：同时是几种的 Bean 说不清被要的是哪一种，宿主拒绝启动。
- **独立服务端**自己设 `wow.kafka.topic-prefix`（`wow.view-store-server.`）：它没有别的聚合。

```yaml
wow:
  view-store:
    kafka:
      topic-prefix: wow.compensation-service.
```

- 改一个运行中部署的前缀，视图存储就搬到新的空主题上：先把旧主题消费完。
- 视图存储的主题是 Wow 缺省主题（两个前缀都没设，总线在 Kafka 上）的宿主，启动时打一条警告，点名 `wow.view-store.kafka.topic-prefix`：它无从知道集群上是否还有别的部署。
- Kafka 集群不自动建主题（`auto.create.topics.enable=false`）时，在宿主启动前建好视图存储的六个主题：`<prefix>view-store.view.{command,event,state}` 与 `<prefix>view-store.view_preferences.{command,event,state}`。补偿服务就是 `wow.compensation-service.view-store.view.command` 和它旁边的五个。
- 视图存储有自己前缀的宿主，在它生成的 BI 脚本（`wow.bi.script`）里不包含视图存储：脚本按 BI 唯一的 `topic-prefix` 读每个聚合的主题，而那个前缀不是视图存储的。

## 存储

### MongoDB

- starter 不建自己的 MongoDB 索引：Wow 没有给模块加快照索引的钩子。视图多时自己在 `view_snapshot` 上加：`state.config.kind`，以及多键的 `state.config.panels.instanceId`、`state.config.panels.opens` 与 `state.config.panels.click.instanceId`。
- 在 MongoDB 上，两个视图存储部署只靠数据库分开：两个宿主连同一个数据库会共用集合（查询把租户、所有者与应用分开，存储不分）。给每个部署一个自己的数据库，补偿服务与独立服务端都是这样。

### Elasticsearch

starter 带着两个快照索引的定义（`META-INF/wow/elasticsearch/wow.view-store.view.snapshot.json` 与 `wow.view-store.view_preferences.snapshot.json`）和视图事件流的定义（`wow.view-store.view.es.json`），索引还不存在时，Wow 在启动时按它们建，与它自己的模板并列：

- 被查询的路径（`state.definitionId`、`state.appId`、`state.config.kind` 与面板引用）是不带 `ignore_above` 的 `keyword`，所以无论 Wow 的模板推断出什么，查询模式都接受视图存储发的过滤。
- `state.config` 是 `dynamic: false`：它装着引擎配置里的一切，包括同一个键下不同类型的值；只有 `kind` 与 `panels`（`nested` 数组，供共享看板检查用，含 `instanceId`、`opens`、`click.instanceId`）是字段。服务端因此拒绝 `panels` 不是至多 1000 个对象的数组、引用不是至多 256 字的字符串的配置，以及超过 256 字的 `definitionId`：存储会拒绝它索引不了的文档。
- 偏好的 `state.lastTabs` 不索引（`enabled: false`）：它的键是宿主的。
- 视图事件里，`body.body`（事件的载荷）是 `dynamic: false`，只有 `audience` 与 `toOwnerId` 是 keyword：重放路由靠它们找到一次收为个人。

**已经存在的索引不会被改。** 宿主在用上带这些定义的 starter 之前就写过视图时，Wow 已经只按模板建了索引，它保留原来的映射，宿主启动时打一条警告，点名映射不同的路径：这样的快照索引上视图列表被拒绝，这样的事件流索引上重放答 400。趁它还空时删掉它，或者在宿主启动前把它重建索引到按定义建的索引里（通用做法见[重建已有索引](./elasticsearch.md#reindex-existing-index)）。事件流的步骤（宿主停着）：

1. `PUT wow.view-store.view.es-new`，请求体 `{"mappings":{"enabled":false}}`，再 `POST _reindex` 从 `wow.view-store.view.es` 到它。这个临时索引不匹配任何 Wow 模板（`wow.*.es`），映射只来自请求体；只映射一部分文档的请求体会让 Elasticsearch 动态映射其余部分，遇到载荷形状不同的事件时重建失败。关掉映射，它只保留 `_source`，回程只需要这个。
2. 删掉 `wow.view-store.view.es`，用 `wow.view-store.view.es.json` 的内容重新 `PUT` 它（其余由 Wow 的模板补上），再 `POST _reindex` 从 `wow.view-store.view.es-new` 回来。
3. 删掉 `wow.view-store.view.es-new`，启动宿主：警告消失。

**索引前缀。** 视图存储的索引没有自己的前缀：两个嵌入 starter 的宿主连同一个 Elasticsearch 集群会共用 `wow.view-store.view.snapshot`（查询把应用分开，存储不分），除非各自设 `wow.elasticsearch.index-prefix`。它移动宿主所有的索引与模板，视图存储的也在内（`<prefix>wow.view-store.view.snapshot`……）；上面那些定义的文件名不变。

## 系统视图

系统视图是一个应用的每个用户都能读、但谁也不拥有的视图。它有三个来源：

| 来源 | 在哪里 | 谁写 |
|---|---|---|
| 代码 | 宿主的视图定义，id 以 `system:` 开头 | 没有人：只读，从不发给服务端 |
| 配置 | `wow.view-store.system-views`（或宿主的 `SystemViewProvider` Bean），按租户与应用（空白即全部） | 没有人：只读，改了要重启 |
| 存储 | 保留租户 `(platform)`、所有者 `(system)` 下的视图聚合，按应用与定义 | 管理员，经常规的视图路由，不必重启 |

`GET …/tenant/{tenantId}/owner/(shared)/system-views[?definitionId=]` 对**任何**请求租户，先答与它的租户和应用匹配的配置视图，再答它这个应用的存储视图；`GET …/system-views/{id}` 先找存储的，再找配置的。每个 `SystemView` 带 `source`（`configured` 或 `stored`），存储的还带 `version`；两者的 `revision` 都是内容的散列。存储视图与配置视图同 id 时，读写都以存储的为准，服务端打一次警告。

### 配置的系统视图

```yaml
wow:
  view-store:
    system-views:
      - tenant-id: ''        # 空白：所有租户
        app-id: my-app       # 空白：所有应用
        definition-id: orders
        id: orders-open
        title: Open orders
        config: |
          {"kind": "record", "columns": []}
```

`config` 是引擎 `ViewConfig` 的 JSON 文本。启动时逐条检查：`config` 必须是 JSON 对象、`kind` 是 `record`、`analysis` 或 `dashboard` 之一，id 不能为空也不能以 `system:` 开头（那是代码声明的），同一租户、应用下的 id 不能重复；不合格就启动失败。

要从别处读（数据库、配置中心），换掉缺省的 `SystemViewProvider` Bean，用 `SystemViews.of` 构建每个视图，它做同样的检查并算出内容散列的 revision：

```kotlin
import me.ahoo.wow.serialization.toObject
import me.ahoo.wow.viewstore.starter.system.SystemViewProvider
import me.ahoo.wow.viewstore.starter.system.SystemViews
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode

@Configuration
class SystemViewConfiguration {
    @Bean
    fun systemViewProvider(): SystemViewProvider = SystemViewProvider { tenantId, appId ->
        // 按请求的租户与应用给出；这里给每个租户、每个应用同一个。
        Flux.just(
            SystemViews.of(
                id = "orders-open",
                definitionId = "orders",
                title = "Open orders",
                config = """{"kind": "record", "columns": []}""".toObject<ObjectNode>(),
            ),
        )
    }
}
```

### 存储的系统视图

存储的系统视图是**全局的**：只放在租户 `(platform)` 下（`ViewStoreService.SYSTEM_TENANT_ID`，即 CoSec 平台租户的值；不是 Wow 的缺省租户 `(0)`，那是不分租户的部署用的），每个租户都读它们。在别的租户下以所有者 `(system)` 创建会被拒绝（`ViewInvalid`）。它们走每个视图都有的路由，只在一条路径上：

```text
POST   /view-store/tenant/(platform)/owner/(system)/view                 创建（「发布」：复制一个视图的标题与配置）
PUT    /view-store/tenant/(platform)/owner/(system)/view/{id}/save
PUT    /view-store/tenant/(platform)/owner/(system)/view/{id}/rename
DELETE /view-store/tenant/(platform)/owner/(system)/view/{id}            删除（「撤下」）
GET    /view-store/tenant/(platform)/owner/(system)/view/requests/{requestId}   重试写入的重放
```

（宿主自己的上下文就是 `view-store` 时没有 `/view-store` 前缀。）

- 系统视图从不改受众：`share` 与 `claim` 被拒绝（`SystemViewReadOnly`）。删掉一个共享看板正在显示的系统视图是允许的，和共享视图一样，那块面板坏掉。
- 服务端生成每个视图的 id，所以经 HTTP 不会意外与配置视图撞 id；要原地替换一个配置视图的宿主，在进程内以那个 id 向聚合 `(platform)`/`(system)`/`<id>` 发 `CreateView`。
- 存储视图经宿主的快照查询后端（MongoDB 或 Elasticsearch）从视图快照里读，每个应用至多 1000 个（超出时警告点名那个应用）。没有快照查询后端的宿主（内存快照）只提供配置视图，启动时打一条警告：存储的系统视图在那里是关的。宿主自己的 `StoredSystemViewSource` Bean 可以替换两者。
- 前端由宿主给 `editSystem` 的人发布与编辑它们（[权限](../typescript/view-engine-storage.md#权限-只管按钮-网关来决定)）。

## 安全模型

### 网关拥有权限

服务端不认证，**身份就是路径**：路径的 `{ownerId}` 就是用户（或 `(shared)`），租户与应用是路径的 `{tenantId}` 与 `CoSec-App-Id` 请求头。服务端没有任何「谁能写」的设置，所有的放行都是 CoSec 网关的路径规则（[CoSec](./cosec.md)）。服务端这一侧只保证路径就是它读到的那个：

- 应用只从 `CoSec-App-Id` 取；调用者发到视图存储路径的每个 `Command-Header-*` 都被丢掉（Wow 会拒绝它保留的键，`command_operator` 与 `app_id` 都在其中，其余的键会原样抄进命令头）；`Command-Tenant-Id` 与 `Command-Owner-Id` 也被丢掉。
- 解码后租户或所有者为空，或含有显示为空白或什么都不显示的字符（空白、控制与格式字符如 U+200B、代理项、私用区与未分配码位，以及别的不可见字符），在到达 Wow 之前答 400 `ViewScopeRequired`：Wow 自身会拒绝空白的已声明路径值（400 `IllegalArgument`），但不拒绝不可见字符，而不可见字符会造出一个看起来像别人的所有者。被收为个人时的新所有者守同样的规则。

### 网关规则

规则把租户、所有者与应用都绑到令牌上（路径都在服务的基础路径下）：

| 路径 | 方法 | 放行条件 |
|---|---|---|
| `/view-store/tenant/{tenantId}/owner/{ownerId}/**`，`…/view/{id}/claim` 与 `…/view/{id}/share` 除外 | 全部 | `{tenantId}` 是令牌的租户，**且** `{ownerId}` 是令牌的 `sub` |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` | `GET`，以及 `POST …/snapshot/**`（读） | `{tenantId}` 是令牌的租户 |
| `/view-store/tenant/{tenantId}/owner/(shared)/**` | `POST /view`、`PUT`、`DELETE`（写） | `{tenantId}` 是令牌的租户，**且**调用者具备写共享视图的角色 |
| `/view-store/tenant/{tenantId}/owner/{ownerId}/view/{id}/claim`、`…/view/{id}/share` | `PUT` | `{tenantId}` 是令牌的租户，`{ownerId}` 是令牌的 `sub`，**且**调用者具备写共享视图的角色 |
| `/view-store/tenant/{tenantId}/owner/(shared)/definitions/{definitionId}/preferences` | `PUT` | 同共享写入（不登录的宿主把偏好存在这里） |
| `/view-store/tenant/(platform)/owner/(system)/**` | 全部 | 调用者是系统视图的管理员（任何租户：这些视图是全局的） |

- **收为个人与设为共享两样都要。** 收为个人把共享视图移给调用者，等于把它从所有人的列表里拿掉；设为共享把个人视图移到 `(shared)`，等于把它发布到所有人的列表里。两者都发到个人路径（`sub == {ownerId}`），还要写共享视图的角色。个人规则不能单独放行它们：把 `…/view/{id}/claim` 与 `…/view/{id}/share` 从个人规则里排除，只由这一条决定；否则谁都能先建个人视图、再设为共享，发布一个共享视图。
- **应用也来自令牌。** CoSec 认证 `CoSec-App-Id`；请求头缺失，或写的应用不是令牌所属的应用，就拒绝。服务端按这个请求头把应用分开。
- **`(shared)` 与 `(system)` 永远不是用户。** 不要签发 `sub` 为 `(shared)` 或 `(system)`、或含括号的令牌：服务端拒绝这样的新所有者，而网关的个人规则会把它放进共享或系统路径。
- **读系统视图不需要单独的规则**：客户端经自己租户的 `…/owner/(shared)/system-views` 读它们。

### 系统视图的写入只给平台管理员

`…/tenant/(platform)/owner/(system)/**` 上的规则决定谁能发布、修改、撤下每个租户都在读的系统视图；个人规则不能放行这条路径（租户 `(platform)` 用户的 `sub` 永远不是 `(system)`）。下面这条 CoSec 策略不区分大小写地匹配这条路径，拒绝平台租户里角色为 `admin` 的用户以外的所有人（这些管理员由部署自己的允许规则放行，这条策略只负责拒绝其余的人）。它没有策略级的 `condition`，因为 CoSec 5.2 拒绝空的 `condition`：

```json
{
  "id": "view-store-system-views",
  "name": "View store system views",
  "category": "view-store",
  "description": "Only platform administrators write the global system views.",
  "type": "global",
  "tenantId": "(platform)",
  "statements": [
    {
      "name": "SystemViewsPlatformAdminOnly",
      "effect": "deny",
      "action": {
        "path": {
          "pattern": "/view-store/tenant/(platform)/owner/(system)/**",
          "options": { "caseSensitive": false }
        }
      },
      "condition": {
        "bool": {
          "or": [
            { "inTenant": { "value": "platform", "negate": true } },
            { "inRole": { "value": "admin", "negate": true } }
          ]
        }
      }
    }
  ]
}
```

**服务端拒绝这条路径的其他写法。** 解码后是 `(platform)`／`(system)`、却写成别的样子的路径（百分号编码、带 `;` 参数、换了大小写）以 `ViewScopeRequired` 拒绝，所以网关在字面路径上的规则看得到每一次系统视图的写入。其余写入视图的途径都关着：Wow 的命令门面与视图存储聚合的批量和维护路由都关闭，发到别的所有者或租户路径的命令过不了 Wow 的所有者检查，或者找不到聚合。

任何路径规则都有两个边界：网关看到的路径必须与服务端路由的一致，所以在网关之前合并重复的斜杠（`/view-store//tenant/…` 若到网关之后才合并，就绕过了字面规则）；直接往命令总线（Kafka）写消息的生产者绕过网关，这和其他视图的信任边界相同。

::: warning
没有 `…/tenant/(platform)/owner/(system)/**` 的网关规则，任何能连到视图存储的人都能创建、修改、删除每个租户的系统视图。
:::

### 前端的按钮跟着同一个角色

`WowViewStore` 的 `permissions`（`createShared`、`instance(id)` 的 `save`／`rename`／`delete`／`changeAudience`，以及引擎的 `editSystem`）由宿主按网关检查的同一个角色给出：`createShared` 与 `changeAudience` 要写共享视图的角色，`editSystem` 要系统视图管理员的角色。服务端不告诉客户端它能做什么。

不登录的宿主（例如补偿控制台）没有令牌：它自己填租户 `(0)`、所有者 `(shared)` 与它的应用，它的网关按网络或服务令牌放行它，和放行那个控制台的其余请求一样（[不登录的宿主](../typescript/view-engine-storage.md#不登录的宿主)）。

## 写入的几条事实

- **创建不幂等。** 重试的 `POST /view` 会以服务端新生成的 id 再建一个视图。保存、改名、改受众、删除与偏好按 `Command-Request-Id` 幂等，重放路由答一个请求 id 的写入留下了什么。
- **共享看板检查读的是快照。** 被共享仪表盘引用的视图不能收为个人；检查查询的是仪表盘的快照，所以刚保存的看板可能还看不到（最终一致）。

## 源码

[`view-store/`](https://github.com/Ahoo-Wang/Wow/tree/main/view-store) · [`ViewStoreProperties.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-starter/src/main/kotlin/me/ahoo/wow/viewstore/starter/ViewStoreProperties.kt) · [`ViewStoreAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-starter/src/main/kotlin/me/ahoo/wow/viewstore/starter/ViewStoreAutoConfiguration.kt) · [服务端配置模板](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-server/src/dist/config/application.yaml) · [`Dockerfile`](https://github.com/Ahoo-Wang/Wow/blob/main/view-store/wow-view-store-server/Dockerfile)
