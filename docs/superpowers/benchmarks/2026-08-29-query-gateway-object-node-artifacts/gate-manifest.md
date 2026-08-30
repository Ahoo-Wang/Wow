# Query Gateway / ObjectNode benchmark gate manifest

## 身份与结论

- Manifest 更新：`2026-08-30`（`Asia/Shanghai`）。
- main baseline：`2e43a9f0d3fee099ee249bc75f55f8678bb27635`。
- V9 committed source：`b56f9cbb39db25ffe4f32d0591b9f6c90d18b8c9`；本 manifest 同时覆盖当前未提交的 ObjectNode/SPI 最小优化。
- 证据完整性：`PASS`。Elasticsearch 与 MongoDB 各 16 个组合，main/V9 各 32 条 mode 记录，共 128 条 JMH 记录，无缺测。
- 正确性与构建门禁：`PASS`。
- 仓库性能预算：throughput、latency、allocation 均为 `10%`；越阈值且区间不重叠才进入候选，候选必须定向确认。
- Task 12 / merge 性能门禁：`PASS_WITH_ACCEPTED_EXCEPTION`。Elasticsearch 定向确认没有已证实回归；MongoDB 的 12 行 allocation 与其中 2 行 throughput 回归已作为 ObjectNode 合约的窄范围例外被明确接受，不引入自定义 BSON→ObjectNode codec。

## 环境

| 项目 | 值 |
| --- | --- |
| Host | Apple M4 Pro，14 logical CPUs |
| OS | macOS 26.5.2 (25F84)，Darwin 25.5.0 arm64 |
| Docker | Desktop 4.88.1；client/server 29.7.2；VM kernel `7.0.12-linuxkit` |
| JDK | Azul Zulu OpenJDK 17.0.7+7-LTS |
| JVM | G1；`-Xms1g -Xmx1g -XX:+UseG1GC` |
| JMH coverage | 1.37；1 thread；3 forks；5 × 200 ms warmup；10 × 200 ms measurement；`thrpt,sample`；`-prof gc` |
| JMH confirmation | 1.37；1 thread；1 fork；5 × 1 s warmup；5 × 1 s measurement；`thrpt,sample`；AB/BA/AB 三轮；`-prof gc` |
| Dataset | 1,000 snapshots；seed `20260829` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:9.2.6` |
| MongoDB | 官方 macOS ARM64 8.3.8；tarball SHA-256 `00e8d49b4ee064cef23a42af09cdf8f1c06cf4cdee16e747db95406843b08472`；`mongod` SHA-256 `f17130625c4abb436006eee52653c6f472cd0d29a16aee880907563c14f46449` |

Docker 中的 MongoDB 8.3.4、8.3.7、8.3.8 都被 Linux 7.0.12 内核兼容性检查拒绝启动。Docker Desktop 更新检查确认 4.88.1 已是当前可用版本后，经明确确认改用官方原生 MongoDB 8.3.8；main/V9 共用同一进程和配置。仓库镜像 pin 未修改。

## Durable artifacts

八份 JMH 原始输出从实际结果逐字节复制，`cmp` 全部返回 0。JSON 保留 fork `rawData`、GC secondary metrics 和 sample percentile；每份 text 保留 16 个 histogram 与 16 个 percentile 表。

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `main-elasticsearch.json` | 3,318,147 | `c0d5f62bfd37d74d06323dd1871453dc2dc68267dadb8defdd94e8ab4edccc5c` |
| `main-elasticsearch.txt` | 524,106 | `23fd475f88f7732db0c8e2f448b1f9915140d910ef76f71ab3f5d6506071befd` |
| `v9-elasticsearch.json` | 3,204,527 | `eaef14cf09a06fac409758455c5ea757c84f2263922bdb7bd0094f4c4c9aecbc` |
| `v9-elasticsearch.txt` | 522,025 | `99e94186c3a55da4549c0614c9efd046eaeb1ea37fb14e13bcbf1a40c429a052` |
| `main-mongo.json` | 5,770,500 | `363be320dc7647758659448a71968daba5e3b829b3f7d34f1724eb9a1c908b3a` |
| `main-mongo.txt` | 543,743 | `756cd5b69cdfcad860c774ee0dc2ca8bccdc142e3c91748b6cb3b082a263fa38` |
| `v9-mongo.json` | 5,672,056 | `d8a86c747cd61598e24fd776b59b529ef56665fe50d143637ab7cdf2a7d5e7d7` |
| `v9-mongo.txt` | 545,975 | `75059fd996cfe8776ab76cc651ae2d84c11466ff751f3d57e13ede6e27adb8a7` |
| `QueryGatewayBackendBenchmark-main.kt` | 13,992 | `fe71b30975917bc6d0660378606d8d2628e394fcc8be153d1f3f223a0506fd86` |
| `META-INF/wow-metadata.json` | 484 | `5753d0aa17512785454f6558e7a19ffddda6ce19925c5d3db59d1a7c90461a30` |
| `paired-elasticsearch.tsv` | 6,111 | `5507d2869fa3e9ceb3bcd7f2eff4a59c1908af0f63884f9464e8275c100b4560` |
| `paired-elasticsearch-summary.tsv` | 1,753 | `3b57c51edb87f5d4252267f9d1ce5ba00529fe83ee0999c152aa1a010a0586fa` |
| `paired-mongo.tsv` | 5,577 | `2db71c1f6c4fe83147a5a6c302f6ab977237f938aa62638cd30bcaf64e91188e` |
| `paired-mongo-summary.tsv` | 1,800 | `2aa4119be5a8f84881a2bbc55c0438d584e8edecd44c4693b8d647432d246673` |

根目录 `.gitattributes` 将该目录的 `*-elasticsearch.{json,txt}` 与 `*-mongo.{json,txt}` 标记为 binary，避免 Git 改写 JMH 生成字节。

## 基准命令与完成时间

main 与 V9 使用各自源码构建的 jar，只替换 storage 与结果路径：

```bash
java -jar wow-benchmarks/build/libs/wow-benchmarks-8.16.1-jmh.jar \\
  '.*QueryGatewayBackendBenchmark.query' \\
  -p storage=<mongo|elasticsearch> \\
  -wi 5 -w 200ms -i 10 -r 200ms -f 3 -t 1 \\
  -bm thrpt,sample -tu s -foe true -prof gc \\
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' \\
  -rf json -rff <result.json> -o <human.txt>
```

| Storage | Run | 完成时间 | Exit | Records / combinations |
| --- | --- | --- | ---: | --- |
| Elasticsearch | main | `2026-08-30T08:06:33+0800` | 0 | 32 / 16 |
| Elasticsearch | V9 | `2026-08-30T08:13:38+0800` | 0 | 32 / 16 |
| MongoDB | V9 | `2026-08-30T08:28:06+0800` | 0 | 32 / 16 |
| MongoDB | adjacent main | `2026-08-30T08:37:59+0800` | 0 | 32 / 16 |

Mongo 的 adjacent main 替换了较早的 main 运行，以降低同机时段漂移。完整比较矩阵见上级 `2026-08-29-query-gateway-object-node.md`。

## 三轮配对确认

仓库现有 baseline comparison 使用 `10%` throughput/latency/allocation 阈值，并要求阈值越界与区间不重叠同时成立；候选本身不直接失败，必须定向确认。本次复用该规则，没有另造预算。

每个候选按 AB/BA/AB 三轮交替 main/V9：

```bash
java -jar <main-or-v9-jmh.jar> '.*QueryGatewayBackendBenchmark.query' \
  -p storage=<storage> -p operation=<operation> -p result=<result> -p masking=<masking> \
  -wi 5 -w 1s -i 5 -r 1s -f 1 -t 1 -bm thrpt,sample -tu s -foe true -prof gc \
  -jvmArgs '-Xms1g -Xmx1g -XX:+UseG1GC' -rf json -rff <pair.json> -o <pair.txt>
```

- Elasticsearch：15 个候选，90 个 JMH 进程 / 180 条 mode 记录；`PASS=1`、`INCONCLUSIVE=14`、确认回归 `0`。所有 allocation 配对区间均在 ±10% 内；throughput/p95 存在明显 AB/BA 顺序效应。
- MongoDB：14 个候选，84 个 JMH 进程 / 168 条 mode 记录；确认回归 `12`、`INCONCLUSIVE=2`。12 行均由 allocation 触发，其中 `list1000/dynamic/inPlace` 与 `paged100/typed/inPlace` 同时确认 throughput 回归；没有确认 p95 回归。
- 三轮比值使用几何均值；95% CI 在对数空间使用 `t(2)=4.303` 计算。成对分数、区间、顺序效应、verdict 与 cause 保存在四份 `paired-*.tsv`。
- 四份 TSV 于 `2026-08-30T10:22:12+0800` 通过行数、唯一 case、verdict count、SHA-256 与 whitespace 校验。
- Mongo 根因：旧 dynamic 直接复用 Mongo `Document`/Map 图；V9 为 ObjectNode 合约构造第二棵树，typed 再物化领域对象。SPI 已删除 bytes→tree 中转，但不会消除这棵必需树。
- 采样结束后，原生 Mongo 进程已正常停止；临时 Elasticsearch benchmark 容器与 network 已通过 compose down 删除。

## Artifact validation

```bash
cmp <each original result> <each durable copy>
shasum -a 256 <ten durable artifacts>
jq -e '<32 rows; 16 combinations; 16 thrpt; 16 sample; forks=3; gc/p95/rawData complete>' \
  {main,v9}-{elasticsearch,mongo}.json
test "$(rg -c '^  Histogram, s/op:$' <human-output>)" -eq 16
test "$(rg -c '^  Percentiles, s/op:$' <human-output>)" -eq 16
```

- 完成时间：`2026-08-30T08:42:53+0800`。
- Exit：`0`。
- 结果：八个 source/durable `cmp`、四份 JSON 结构断言、四份 text 计数和 SHA-256 均通过。

## 最终代码与文档门禁

### 变更模块

```bash
./gradlew :wow-query:check :wow-mongo:check :wow-elasticsearch:check \
  --stacktrace --rerun-tasks
```

- Exit：`0`。
- Tail：`BUILD SUCCESSFUL in 18s`；`49 actionable tasks: 49 executed`。

### 全量构建

```bash
./gradlew build --rerun-tasks
```

- Exit：`0`。
- 最终 fresh run：`BUILD SUCCESSFUL in 1m 48s`；`335 actionable tasks: 335 executed`。

### Elasticsearch 真实后端集成

```bash
./gradlew :wow-elasticsearch:integrationTest --stacktrace --rerun-tasks
```

- Exit：`0`。
- 最终 fresh run：`BUILD SUCCESSFUL in 2m 1s`；`38 actionable tasks: 38 executed`。
- Mongo 当前代码路径已由原生 MongoDB 8.3.8 的 V9 16 组合 JMH 实际执行；Testcontainers Mongo integrationTest 未重跑，因为 Docker VM 内核会在测试启动前拒绝 Mongo 容器。

### 文档

```bash
cd documentation && pnpm docs:build
```

- 最终 fresh run：Exit `0`；`build complete in 24.35s`。
- 仅有既有 chunk-size warning。

### SPI 打包与静态扫描

```bash
jar tf wow-mongo/build/libs/wow-mongo-8.16.1.jar | \\
  rg 'MongoJacksonModule|META-INF/services/tools.jackson.databind.JacksonModule'
unzip -p wow-mongo/build/libs/wow-mongo-8.16.1.jar \\
  META-INF/services/tools.jackson.databind.JacksonModule

! rg -n "QueryService|DynamicDocument|SimpleDynamicDocument|TailSnapshotQueryFilter|TailEventStreamQueryFilter|DataMasking|DYNAMIC_SINGLE|DYNAMIC_LIST|DYNAMIC_PAGED" \\
  wow-*/src test example compensation --glob '!**/build/**'
! rg -n "class QueryRouter|interface QueryRouter|QueryGatewayFactory|AggregatedQueryGateway|Query.*Proxy|Proxy.*Query" \\
  wow-*/src test example compensation --glob '!**/build/**'
! rg -n "BeanFactory" wow-webflux/src/main --glob '!**/build/**'
git diff --check
```

- 最终完成时间：`2026-08-30T10:40:56+0800`。
- Exit：`0`。
- jar 同时包含 `MongoJacksonModule.class` 与精确 service 路径，service 内容为 `me.ahoo.wow.mongo.MongoJacksonModule`。
- 三组静态扫描零匹配，diff whitespace 检查无诊断。

## 放行边界

正确性、打包、文档、覆盖矩阵与配对证据均已通过。性能预算复用仓库既有 10% 规则；Elasticsearch 没有确认回归。Mongo ObjectNode 的确定性 allocation 与伴随吞吐成本已按窄范围接受，Task 12 完成；不增加自定义 codec、第二套 ObjectMapper、reader cache 或 serializer factory。
