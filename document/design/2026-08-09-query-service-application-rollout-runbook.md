# Query 服务目标应用发布与回滚 Runbook

## 1. 适用范围

本 Runbook 用于把一个精确的 `QueryTarget(context, aggregate, documentKind)` 从兼容执行逐步切到
`QueryGateway` planned Backend。它覆盖记录查询与 Snapshot Analytics 的应用接线、SHADOW、PLANNED 和回滚，
不替代 Elasticsearch 索引生命周期 Runbook，也不授权任何生产写入、索引删除或 alias 切换。

每次发布只允许选择明确的 `target + operation`。禁止用全局开关同时切换所有 Aggregate；禁止把
`wow.query.gateway.legacy-wiring-rollback` 当作普通回滚手段，该开关会绕过 admission、policy 和生命周期保护。

以下条件任一成立时必须停止：

- 应用没有从已认证上下文生成的 `QueryAuthority`，或仍把 path/header/调用方自报 id 当作 authority；
- 目标没有经过评审的逻辑 `QueryDocumentSchema` 和精确 Backend binding；
- Backend readiness、mapping/index、Mongo collation 或 text/keyword 完整性证据缺失；
- mandatory tenant/owner/space/deleted 字段无法在 Backend 中无损执行；
- 目标操作会落入 silent fallback、近似 total、partial result 或未受控 unbounded stream；
- Elasticsearch stable 名仍是同名 concrete index，却计划直接创建同名 alias；
- EventStream 迁移依赖尚未实现的 `EVENT_STREAM_CONTROLLED_MIRROR` 或不可证明的生产 watermark。

## 2. 发布前固定输入

变更单必须固定以下内容，执行期间不得动态修改：

| 输入 | 必填证据 |
|---|---|
| Target | `contextName`、`aggregateName`、`documentKind` |
| Operations | `SINGLE/STREAM/PAGE/COUNT/ANALYZE` 的独立清单 |
| Schema | `schemaContractId`、字段类型/operator/capability、search scope |
| Backend binding | `backendId`、`capabilityDigest`、物理 namespace/index generation、readiness report |
| Authority | resolver/provider 代码版本、tenant/owner/space grant 负测、缺失/错误 authority 的 storage-zero 证据 |
| Budget | deadline、returned/scanned/page/candidate bucket/cursor page 上限及目标应用阈值 |
| Cursor | store owner、HMAC current/previous key id、TTL、容量、reaper owner；无 cursor 的操作写 `N/A` |
| Probe suite | 固定 suite id、canonical query/input、expected identity/order/value/total/completeness |
| Observability | shadow outcome、fallback reason、Backend error、deadline、latency、resource 和 cursor 指标面板 |
| Rollback | profile 回切负责人、目标恢复时间、索引/lease 处置、证据保存位置 |

安全与 exact semantic 阈值固定为零差异，不能由目标应用放宽。延迟、吞吐、CPU、内存、扫描量等性能阈值必须由目标
应用基于可重复负载签署，框架不提供伪造的通用默认值。

## 3. 应用接线预检

### 3.1 可信上下文

1. HTTP 使用 `QueryWebAuthorityResolver` 从认证结果生成 authority；tenant/owner/space 只是 selector；
2. 新进程内调用使用显式 `QueryCall` 与 `QueryAuthorityResolver`；
3. 兼容七方法只能选择预注册的 exact `QueryLegacyGrant`；grant 必须同时固定 target、purpose、mode 和 scope；
4. authority provider 的 empty/error、selector mismatch、跨 tenant、无权限字段和 Native 请求全部在 storage 前拒绝；
5. 不允许默认 `System` authority，不允许从可猜字符串或公开 Reactor context key 注入 trusted authority。

### 3.2 Schema 与 Backend binding

1. Schema 只包含逻辑字段，不包含 `.keyword`、Mongo `_id`、ES index 名或 driver 对象；
2. binding 覆盖 Schema 的每个可执行字段，并固定 system field、value encoding、collation/analyzer/normalizer、nested owner；
3. contribution 的 target、schema contract、capability digest 与当前 storage route 精确一致；
4. Mongo/Elasticsearch readiness 在应用启动或显式管理步骤中 fail closed；未 ready 不能广告 capability；
5. record/analytics compiler 只接收 validated Plan，拒绝 RAW/未绑定 Native/未声明 Search；
6. identity 独立返回，logical projection 不泄漏 Backend 为执行而补取的物理字段。

### 3.3 运行时与资源

1. `QueryExecutionProfiles` 默认保持 `LEGACY + COMPATIBLE`，只为本次 target/operation 添加 override；
2. SHADOW 必须配置有界 supervisor 和 observation sink；拒绝、饱和、超时不能静默丢失；
   `QueryShadowObserver` 与 `QueryRuntimeHealthObserver` 都必须实际接入，后者固定记录 fallback、shadow supervisor failure
   与 cursor cleanup failure 的低基数 reason code；
3. Snapshot cursor 需要匹配 target/backend 的持久化 lease store 和 lifecycle closer；
   首次签发的完整 budget ceiling 会进入签名 lease，continuation 只能保持或收紧，不能通过下一页放宽扫描、返回、窗口、
   bucket、cursor page 或 `allowDiskUse`；
4. cursor reaper 只能有一个 lifecycle owner；Mongo TTL 是遗弃 lease 的最终安全网，不是正常关闭路径；
5. Elasticsearch PIT、Mongo Publisher、deadline 与 cancel 的资源释放回归必须在目标版本上通过。

## 4. 分阶段执行

### 4.1 LEGACY 基线

- 保持目标 operation 为 `LEGACY`；
- 运行固定 probe suite，记录结果 checksum、错误分类、p50/p95/p99、扫描量和资源基线；
- 证明所有入口已经经过 Gateway，即使最终由 legacy Backend 执行；
- 清空未知 fallback reason；无法规范化的已知请求必须登记 owner 和迁移方案。

### 4.2 SHADOW

- 只把一个 target/operation 改为 `SHADOW`；validation 建议先 `STRICT`；
- 返回值始终来自 legacy primary；planned probe 只能由有界 supervisor 订阅；
- 观察窗口必须覆盖正常、空结果、边界分页、mandatory scope、超时、取消和错误请求；
- `VALUE_MISMATCH/PROBE_ERROR/INCOMPLETE_RESULT/MAPPING_FAILURE` 阈值为零；
- `SKIPPED/SATURATED` 必须有原因并低于目标应用签署阈值，否则不得进入 PLANNED。

### 4.3 PLANNED canary

- 只切已完成 SHADOW 签署的 operation；不要把未支持的 PAGE/ANALYZE/unbounded 一并切换；
- planned route missing/not-ready、schema/mapping generation drift 必须在 storage 前失败，禁止自动回 legacy；
- canary 期间同时验证 HTTP、聚合 Bean、新进程内 Gateway 和后台任务入口；
- 对 Snapshot Analytics 验证 EVENTUAL continuation 与 SNAPSHOT PIT continuation、terminal/error/cancel/expiry；
- 达到应用签署的最短窗口与样本量后，才扩大流量或下一个 operation。

### 4.4 完成切换

- 每个 operation 单独保存 `LEGACY -> SHADOW -> PLANNED` 的时间、配置 diff、probe 结果和指标快照；
- fallback reason 必须为零；unsupported operation 继续显式 LEGACY/unsupported，不包装成完成；
- 保留 legacy Backend 和旧索引 generation 至少一个已批准的 rollback window；
- Elasticsearch alias cutover 另按
  [Elasticsearch 查询索引迁移与回滚 Runbook](./2026-08-08-elasticsearch-query-index-migration-runbook.md) 执行。

## 5. 回滚

触发条件包括任何语义/安全差异、incomplete result、mapping failure、cursor 泄漏、deadline/cancel 失效、性能阈值越界或
目标应用验收失败。

1. 先把精确 target/operation profile 回到 `LEGACY`；
2. 证明新请求不再命中 planned Backend，raw legacy 调用恢复；
3. 等待或显式关闭已存在的 PIT/cursor lease，不删除仍在 TTL/rollback window 内的 state；
4. 如果同时发生 Elasticsearch alias 迁移，只能在 source 完成 cutover 后新 verification 时回切；
5. 保存 planned error、shadow observation、profile diff、cursor/index 状态和回滚完成时间；
6. 只有 Gateway 本身阻断所有兼容流量且经过单独审批时，才允许限时使用 legacy wiring rollback；恢复后立即关闭。

## 6. 签署记录模板

```yaml
queryRolloutEvidence:
  target:
    contextName: ""
    aggregateName: ""
    documentKind: SNAPSHOT
  operations: []
  artifactVersion: ""
  gitCommit: ""
  schemaContractId: ""
  backendId: ""
  capabilityDigest: ""
  mappingGeneration: ""
  authorityReview: ""
  probeSuiteId: ""
  phases:
    legacyBaseline: { startedAt: "", completedAt: "", evidence: "" }
    shadow: { startedAt: "", completedAt: "", evidence: "" }
    plannedCanary: { startedAt: "", completedAt: "", evidence: "" }
    rollbackRehearsal: { startedAt: "", completedAt: "", evidence: "" }
  semanticMismatchCount: 0
  securityMismatchCount: 0
  incompleteResultCount: 0
  fallbackCount: 0
  performanceThresholds: "application-approved evidence link"
  cursorAndIndexState: "evidence link or N/A"
  rollbackOwner: ""
  applicationOwnerApproval: ""
  securityApproval: ""
  operationsApproval: ""
```

空字段、未附 evidence 的零值、只引用单元测试、或仅写“CI 通过”都不能作为生产签署。

## 7. 当前明确未闭环的边界

- `EVENT_STREAM_CONTROLLED_MIRROR`：现有 EventStore 没有全局单调 watermark 与 `scanAsOf(watermark)`，继续 fail closed；
- 同名 concrete Elasticsearch index 到 managed alias：需要停写、备份、权威重建及删除/改名审批；工具不会自动执行；
- Snapshot alias CUTOVER 默认由 `ElasticsearchSnapshotCutoverGuard.DENY` 拒绝；目标应用没有受信 pause/drain 或 controlled
  mirror 证明时，`CUTOVER_FENCE_REQUIRED` 是预期结果，不得临时替换为测试 allow guard；
- Mongo 精确 scanned-record enforcement、目标数据分布性能阈值、ES mapping inventory：必须由目标应用实测；
- EventStream analytics、unbounded stream 和未证明的 string/search capability：保持 unsupported。

仓库内的真实 Mongo/Elasticsearch 容器演练和 `example-service/order` Spring mode 回归只证明框架机制可执行，不能替代
本模板要求的生产 authority、数据、mapping、流量与负责人签署。
