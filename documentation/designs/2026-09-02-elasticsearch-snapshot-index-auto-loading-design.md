# Elasticsearch 快照索引自动加载设计

日期：2026-09-02
状态：已批准

## 背景

Wow 当前通过通用 `wow-snapshot-template` 支持 Elasticsearch SnapshotStore 开箱即用。该模板固定快照系统字段、标签和常见 ID 字段，但无法为每个聚合的业务状态预先选择 `keyword`、`text`、数值、时间、`nested` 或 analyzer 等 mapping。

通用模板适合仅把 Elasticsearch 当作快照存储的场景。快照同时承担查询模型时，动态 mapping 不能稳定提供所需的精确匹配、全文、排序、范围和聚合能力。应用应提供面向具体聚合的原生 Elasticsearch 索引配置，由 Wow 在首次写入前创建具体索引。

## 目标

- 保留通用 snapshot template，继续支持纯快照存储。
- 允许应用用 Elasticsearch 原生 Create Index JSON 声明具体快照索引。
- 在 SnapshotStore 完成装配前自动发现并创建缺失索引。
- 统一 Wow 的 classpath 与工作目录资源规范。
- 让 Query Schema 识别统一规范，同时兼容现有目录。
- 不新增依赖，不定义 Elasticsearch JSON 的 Wow 专用 DTO。

## 非目标

- 不更新、合并或 reconcile 已存在索引的 mapping/settings。
- 不自动执行 reindex、alias 切换或数据迁移。
- 不根据 Query Schema 生成 Elasticsearch mapping。
- 不替 Elasticsearch client/cluster 校验原生 Create Index JSON 的未知字段。
- 不根据 storage routing 判断是否应创建资源声明的索引。
- 不移除通用 snapshot template，也不改变 `auto-init-template` 默认值。
- 不扩展到 EventStore concrete index；需要时再按同一规范增加。

## 统一资源规范

所有新式 Wow 配置资源使用以下根目录：

```text
# 随应用或依赖打包
META-INF/wow/{feature}/{resourceKey}.json

# 应用工作目录覆盖
config/wow/{feature}/{resourceKey}.json
```

资源定位器位于 `wow-core`，仅使用 JDK API。它负责：

- 构造统一路径；
- 校验 feature/resource key，拒绝空值、路径分隔符和目录穿越片段；
- 定位工作目录资源；
- 枚举同名 classpath 资源；
- 暴露可诊断的资源位置并保留读取失败的 cause。

定位器不解析 JSON，也不规定多资源合并策略。Query Schema 和 Elasticsearch 分别拥有自己的解析、优先级与冲突语义。

### Query Schema

Query Schema 使用：

```text
feature     = query-schema
resourceKey = {contextName}.{aggregateName}.{model}
```

示例：

```text
META-INF/wow/query-schema/sales.order.snapshot.json
config/wow/query-schema/sales.order.snapshot.json
```

`model` 沿用现有小写值，例如 `snapshot`、`event_stream`。
点号沿用 Wow named aggregate 的保留分隔符约定；上述名称片段不再增加转义或编码层。

迁移规则按来源分别处理：

- 工作目录先查新路径；不存在时回退 `config/wow-query-schema/{contextName}/{aggregateName}/{model}.json`。
- classpath 先枚举新路径；没有新资源时回退 `wow-query-schema/{contextName}/{aggregateName}/{model}.json`。
- 工作目录声明继续以现有高优先级覆盖 classpath 声明；classpath 多声明继续按现有规则合并。
- 同一来源层不会同时加载新旧路径，避免重复声明。

### Elasticsearch 快照索引

Elasticsearch 使用：

```text
feature     = elasticsearch
resourceKey = {snapshotIndexName}
```

示例：

```text
META-INF/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json
config/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json
```

`snapshotIndexName` 必须由现有 `NamedAggregate.toSnapshotIndexName()` 计算，资源文件不拥有或覆盖索引名。

工作目录文件整体覆盖 classpath 文件。若工作目录文件不存在，classpath 中同名配置必须恰好为零或一份；多份配置是歧义并导致启动失败。Elasticsearch 配置不做跨文件合并。

## Elasticsearch 索引配置合同

文件内容直接采用 Elasticsearch Create Index 请求体，可包含 `settings`、`mappings` 和 `aliases`：

```json
{
  "settings": {
    "index.number_of_shards": 1
  },
  "mappings": {
    "properties": {
      "state": {
        "properties": {
          "status": {
            "type": "keyword"
          }
        }
      }
    }
  }
}
```

Wow 不复制 Elasticsearch 的字段级校验规则，也不改变 client 对未知字段的处理方式。JSON 由 Elasticsearch client 解析为原生 Create Index 请求；其支持与校验语义以 client/cluster 为准。请求的 index 参数始终由 Wow 设置。

## 组件

### 统一资源定位器

在 `wow-core` 的 configuration 包中增加一个具体的 JDK-only 资源定位器，不增加接口或工厂。构造参数允许测试替换工作目录和 ClassLoader。它分别提供工作目录单资源定位与 classpath 多资源枚举，使调用方能保留各自语义。

### Query Schema Sources

`WorkingDirectoryQuerySchemaSource` 和 `ClasspathQuerySchemaSource` 改用统一定位器。Query Schema 自己实现新路径优先、旧路径回退以及原有 declaration 解析和合并，不把业务规则下沉到定位器。

### Elasticsearch Snapshot Index Initializer

`wow-elasticsearch` 增加一个具体 initializer。它：

1. 遍历 `MetadataSearcher` 中的已知聚合；
2. 计算每个聚合的最终 snapshot index name；
3. 查找对应的工作目录/classpath 配置；
4. 对存在的配置构造原生 Create Index 请求；
5. 已存在索引直接跳过，否则创建并要求 acknowledgment。

只处理找到配置文件的聚合。资源存在本身就是创建该 Elasticsearch 索引的显式声明；不额外解析 storage routing，也不为此引入启动期 bean 依赖环。

### Spring Boot 装配

Elasticsearch SnapshotStore 的装配顺序为：

1. `auto-init-template=true` 时确认通用 snapshot template；
2. 执行 concrete snapshot index initializer 并等待完成；
3. 创建 `ElasticsearchSnapshotStore`。

custom index 初始化不受 `auto-init-template` 开关控制。只要用户提供具体索引资源，它就会被处理；关闭 template 初始化时，用户配置必须独立满足目标索引需求。

不增加新配置开关。没有 concrete index 资源时，initializer 是 no-op。

## 启动数据流

```text
Elasticsearch SnapshotStore selected
        |
        v
Ensure generic snapshot template (when enabled)
        |
        v
For each known aggregate -> compute snapshot index name
        |
        v
Resolve config/wow/elasticsearch/{index}.json
        |
        +-- found --> use working-directory resource
        |
        +-- absent --> resolve META-INF/wow/elasticsearch/{index}.json
                          |
                          +-- none --> skip; generic template remains fallback
                          +-- one  --> parse native Create Index JSON
                          +-- many --> fail startup
                                            |
                                            v
                                  Index exists? -- yes --> skip
                                            |
                                            no
                                            v
                              Create index and require acknowledgment
                                            |
                                            v
                              Construct ElasticsearchSnapshotStore
```

## 失败与并发语义

- 工作目录资源一旦存在，读取或解析失败立即失败，不回退 classpath。
- classpath 枚举失败、同名配置重复、原生请求解析失败、创建请求失败、空响应或未确认响应都使启动失败。
- 异常包含索引名与资源位置，并保留原始 cause。
- 已存在索引不比较、不解析集群中的现有 mapping，也不应用配置更新。
- 多实例并发启动时，exists 检查后出现 `resource_already_exists_exception` 表示另一实例已达到目标状态，按成功处理。
- 除该精确并发错误外，不吞掉 Elasticsearch 错误。
- 配置资源可在检查索引前完成读取和语法解析；索引已存在时不向集群验证配置的 setting/mapping 语义。

## 兼容性

- `wow.elasticsearch.auto-init-template` 的名称、默认值和行为保持不变。
- 没有新资源的应用保持现有 snapshot template 与自动建索引行为。
- Query Schema 旧目录继续可用；新目录按来源优先，避免同层重复加载。
- 已存在索引不受新机制修改。
- 新机制只增加 source compatibility，不改变 SnapshotStore 或 Query Backend 公共接口。

## Compensation 示例迁移

将当前未被自动加载的：

```text
compensation/wow-compensation-server/src/main/resources/indexs/execution_failed_index.json
```

迁移为：

```text
compensation/wow-compensation-server/src/main/resources/META-INF/wow/elasticsearch/wow.compensation.execution_failed.snapshot.json
```

文件保持原生 Create Index JSON 结构，作为生产代码中的真实规范样例。

## 测试

### wow-core

- 统一路径生成；
- 工作目录与 classpath 定位；
- 多 classpath 资源枚举；
- 非法 feature/resource key；
- 列举和读取失败保留 cause。

### wow-query

- 新 classpath 路径加载；
- 新工作目录路径加载；
- 各来源的新路径缺失时回退旧路径；
- 新旧同层不重复加载；
- 工作目录高优先级与 classpath 多声明合并语义不变；
- refresh 重新读取资源。

### wow-elasticsearch

- 无资源时不请求索引；
- 工作目录资源覆盖 classpath；
- 重复 classpath 配置失败；
- 已存在索引跳过创建；
- 原生 JSON 的 settings/mappings/aliases 进入 Create Index 请求；
- 未确认、读取、解析和请求失败；
- 并发 `resource_already_exists_exception` 按成功处理；
- 其他 Elasticsearch 错误继续失败。

真实 Elasticsearch 集成测试验证 concrete index 被创建、mapping 生效，并能被 Query Schema mapping adapter 识别为相应查询能力。

### wow-spring-boot-starter

- snapshot template 先于 concrete index 初始化；
- concrete index 初始化完成后才创建 SnapshotStore；
- template 自动初始化关闭时仍处理用户索引资源；
- 初始化失败导致 Spring context 启动失败。

### compensation

- 资源位于统一目录且最终索引名与聚合 metadata 一致；
- 现有查询 schema 测试继续通过。

## 文档

同步更新中英文：

- Elasticsearch 扩展指南；
- 基础设施配置参考；
- Query Schema 资源目录说明；
- custom snapshot index 的 create-only、失败和迁移语义。

文档必须明确：通用 template 面向纯快照存储；将快照用于查询时，应提供具体索引配置并为已有索引安排显式 reindex/migration。

## 验证命令

```bash
./gradlew :wow-core:check
./gradlew :wow-query:check
./gradlew :wow-elasticsearch:check
./gradlew :wow-elasticsearch:integrationTest
./gradlew :wow-spring-boot-starter:check
./gradlew :wow-compensation-server:check
```
