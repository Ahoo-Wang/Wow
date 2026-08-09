# Elasticsearch 查询索引迁移与回滚 Runbook

## 1. 适用范围

本 Runbook 只适用于 Query Gateway 版本化索引生命周期工具管理的 Elasticsearch 索引：

- stable alias 已指向一个受管 physical generation；
- physical index 名符合 `<stable-alias>-v<4-digit-mapping-version>-<6-digit-generation>`；
- Snapshot 使用 `SNAPSHOT_FROM_EVENT_STREAM` 权威重建；
- EventStream 仅使用经过审批、由外部 write-fence/barrier 提供稳定 watermark 的 pause-and-drain；
- mapping、logical schema 与 planned binding 已生成一致的 `schemaContractId` 和 `capabilityDigest`。

工具不会在应用启动时创建 system index、创建 template/physical index、重建数据、切换 alias 或删除旧索引。
所有动作必须由显式管理命令触发。

以下场景不在当前工具的安全执行范围内：

- stable 名当前是 concrete index，而不是 alias；
- source generation 不是 manifest 声明的 exact physical index；
- 计划依赖未验证的动态 `.keyword`、analyzer/normalizer、nested mapping 或 text index；
- migration 未使用与 document kind 匹配的 manifest 固定 checksum，目标应用尚未配置可重复的
  record/analytics probe suite，或 EventStream 没有可证明停写并排空的生产 barrier；
- EventStream 选择 `EVENT_STREAM_CONTROLLED_MIRROR`；当前 internal vertical slice 会稳定拒绝该策略；
- 需要删除、改名或覆盖现有索引。

上述任一条件成立时必须停止。特别是 concrete index 到同名 alias 的转换，Elasticsearch 不允许两者同时存在，
需要独立审批的停写、备份、权威重建、删除/改名和恢复演练，不能复用本 Runbook 静默处理。

## 2. 不变量与职责

| 边界 | 不变量 |
|---|---|
| Manifest | 精确绑定 target、mapping version、generation、schema contract、capability digest、source physical、重建策略、checksum algorithm、probe suite、cursor TTL 与 rollback window |
| Repository | `.wow-query-index-lifecycle-v1` 由管理工具显式创建；repository document/mapping format 为 `v1`，有界 lifecycle payload codec 为 `v2`；document create 注册，`_seq_no + _primary_term` CAS 更新；旧/损坏/跨 migration payload fail closed，不做隐式迁移 |
| Template | component template 先于 composed index template；`allow_auto_create=false`；mapping `_meta` 必须与 manifest 完全一致 |
| Rebuild | Snapshot 由框架按 AggregateId 分页并从权威 EventStore 全量重放，幂等写 exact physical generation；禁止把旧索引 `_reindex` 当作权威重建 |
| Verify | authority 与 exact physical index 使用 document-kind-specific canonical checksum；count、identity/content checksum、version continuity、watermark、record/analytics probe 必须全部满足，差异阈值固定为零 |
| Cutover | destination verification 之后仍必须由目标应用提供受信的写栅栏。Snapshot 默认 guard 为 deny；只有 pause/drain 或受控 mirror 证明写入窗口已封闭，才允许单个 `_aliases` 请求完成切换并回读确认 |
| Retention | source 至少保留 `maxCursorTtl + rollbackWindow`；工具不删除 generation |
| Rollback | cutover 后必须对 source 做一次新的 verification；旧报告不能复用 |

## 3. 迁移前检查

在变更单中固定以下输入，执行期间不得动态改写：

1. `migrationId`、每一步唯一 `commandId`、目标 Aggregate 与 document kind；
2. source alias、source physical、destination physical；
3. mapping version/generation、`schemaContractId`、`capabilityDigest`；
4. Snapshot 权威事件流范围或 EventStream pause/drain/mirror 方案；
5. `maxCursorTtl`、`rollbackWindow`、最早允许删除旧 generation 的时间；
6. manifest `verificationContract`：Snapshot 使用 `CANONICAL_DOCUMENT_SHA256_V1`，EventStream 使用
   `CANONICAL_EVENT_STREAM_SHA256_V1`，并固定经过审批的 record/analytics probe suite id；
7. Query execution profile 的当前值、SHADOW 观测窗口、回退负责人；
8. Elasticsearch 集群备份/恢复证据与变更窗口。

执行只读预检：

- alias 恰好有一个未过滤、无 routing 的 write index；
- source physical 存在并与 alias target 相同；
- destination 不存在，或其 mapping attestation 与 manifest 完全相同；
- planned binding readiness 已通过，未出现 `_ignored`、超长 exact 值或 mapping drift；
- lifecycle repository system index mapping/document format 为 strict `v1`，payload codec 必须为当前 `v2`；
- 没有另一个 active command；本次 command 的 `expectedRevision` 等于持久化 state revision。
- Snapshot CUTOVER 已配置目标应用受信的 `ElasticsearchSnapshotCutoverGuard`；默认 `DENY` 会稳定返回
  `CUTOVER_FENCE_REQUIRED`。仅有 destination verification、SHADOW 零差异或一次 checksum 相等都不能替代写栅栏。

任一预检失败不得进入 CREATE。

## 4. 标准执行序列

每一步先执行 dry-run/plan，人工核对 `from`、`to`、`expectedRevision`、target 与 physical index，再执行命令。
同一步重试必须复用原 `commandId`；不得用新 command 绕过 active command。

### 4.1 初始化 repository 与注册 manifest

1. 显式执行 repository `ensureIndex()`；重复执行必须幂等；
2. 注册 immutable manifest；相同 `migrationId` 绑定不同 manifest 必须失败；
3. 保存返回的 state revision、Elasticsearch `_seq_no` 与 `_primary_term` 到变更证据。

### 4.2 VALIDATE

读取 alias 与所有受管 generation，确认 source/destination attestation。状态必须从 `NEW` 进入 `VALIDATED`。

### 4.3 CREATE

幂等写 versioned component/index template，显式创建 destination physical index并回读 `_meta`。状态必须从
`VALIDATED` 进入 `CREATED`。禁止把 alias 加到 destination。

### 4.4 REBUILD

按 manifest 策略从权威源写 destination：

- Snapshot：使用 EventStore authoritative rebuilder 按 aggregate identity/version 重放全部事件；source page 必须严格递增且不超过请求上限，结果通过 version guard 幂等写入 manifest exact physical index；
- EventStream pause/drain：外部系统先停止新写并排空已接受写入；工具在复制前后读取同一 barrier watermark，只有
  watermark 稳定、aggregate/page/version/event-body 契约完整时才签发 receipt；
- EventStream controlled mirror：当前未实现，工具稳定拒绝，不得用 pause/drain 实现冒充。

receipt 必须记录 authoritative/indexed watermark 与完成时间。状态从 `CREATED` 进入 `REBUILT`。

### 4.5 VERIFY destination

针对 destination 重新计算完整 verification：

- expected/actual count；
- identity checksum；
- content checksum；
- aggregate/event version continuity；
- authoritative/indexed watermark；
- record probe mismatch count；
- analytics probe mismatch count。

全部满足后状态从 `REBUILT` 进入 `VERIFIED`。任何差异都保持 alias 指向 source。

Snapshot 的 `CANONICAL_DOCUMENT_SHA256_V1` 固定以下语义：EventStore 全量重放结果与 exact physical index 都按
`aggregateId` 严格递增扫描；object key 排序、array 顺序保留、数学等价 number 规范化，identity 与每条 canonical
document 分别做 length-prefixed SHA-256；只排除重建时生成的顶层 `snapshotTime`。physical 端在 PIT 内使用
`aggregateId > lastIdentity` keyset 分页，拒绝 partial shard、非 exact/不稳定 total、identity/source 不一致、缺失或
负 version、重复/乱序和超预算 document。算法或 probe suite 与 manifest 不一致时 verification fail closed。

EventStream 的 `CANONICAL_EVENT_STREAM_SHA256_V1` 固定以下语义：authority 端按 aggregate id 分页读取完整
`DomainEventStream` 历史，physical 端在 PIT 内按 `(aggregateId, version)` keyset 扫描；两端都要求 aggregate
严格递增、每个 aggregate 从初始版本开始且版本按 event count 连续、document id 等于 `aggregateId-version`、body
非空，并对完整 canonical document 生成 identity/content checksum。authority 与 destination watermark 必须分别在扫描
前后保持稳定且最终相等。工具只验证外部 barrier 的证明，不负责主动暂停生产 writer。

Probe suite 精确绑定 manifest 固定的 suite id、`QueryTarget` 与 `SchemaContractId`，且只接受预注册的 probe id；不接受
临时 wire query、driver object 或 `Any` payload。
internal bounded runner 最多执行 256 个 canonical probe，分别从 authority 与 exact physical generation 取得完整、不可变的
`resultCount + resultChecksum` evidence；任一 empty、incomplete、executor error 或 typed error 都统一为 verification failure。
record 与 analytics mismatch 分开计数且阈值都固定为零。具体 probe catalog、authority evaluator 和 physical evaluator 必须由
目标应用提供并经过评审；仅有 runner 不构成目标应用验收证据。

### 4.6 CUTOVER

`VERIFIED` 只证明 verification 时刻 destination 与权威状态一致，不会阻止 Snapshot writer 在 verification 与 alias 切换之间
继续写 source。执行 CUTOVER 前，外部系统必须暂停/排空目标 Snapshot 写入，或启用经过审批、可证明 destination 已追平的
controlled mirror；随后由目标应用实现的 `ElasticsearchSnapshotCutoverGuard` 对 exact manifest 与 verification 签发一次性
许可。仓库默认 guard 是 `DENY`，本次代码/PR 没有生产 guard，也不授权任何真实 alias 切换。

只有 guard 成功后，才在批准窗口执行一次 CAS alias transition。完成后：

1. 回读 alias，确认唯一 write index 是 destination；
2. 保存 transition timestamp 与 `retainedSourceUntil`；
3. Query profile 先保持 SHADOW，核对 error/diff/incomplete 指标；
4. 不删除、不封闭 source generation。

状态从 `VERIFIED` 进入 `CUTOVER`。

### 4.7 VERIFY source for rollback

cutover 后对 source 重新执行 verification，证明它仍满足当前权威状态。只有新报告通过，状态才从 `CUTOVER`
进入 `ROLLBACK_VERIFIED`。EventStream 若未持续 mirror，通常不能通过此步骤，也不得声称可直接回切。

## 5. 回滚

触发条件包括语义差异、incomplete result、mapping failure、性能阈值越界或业务验收失败。

1. 先把目标 Query execution profile 回到 `LEGACY`，停止 planned 业务流量；
2. 确认 state 为 `ROLLBACK_VERIFIED`，source verification 是 cutover 后的新报告；
3. 执行 `ROLLBACK` CAS alias transition：remove destination，add source 为唯一 write index；
4. 回读 alias并执行 smoke/probe；
5. destination 保留供审计，不删除；
6. 若 source 不再新鲜，禁止直接回切，改为从权威源新建 generation。

`wow.query.gateway.legacy-wiring-rollback` 会绕过新的 admission/policy，不是常规索引回滚手段，只能作为单独审批、
限时的框架 wiring 应急开关。

## 6. 故障恢复

| 故障 | 处理 |
|---|---|
| 管理进程在外部动作前退出 | 读取持久化 state；若无 active command，按 revision 重新 plan |
| 管理进程在外部动作后、state 完成前退出 | 使用原 command id 重试；外部 port 必须幂等，Executor 恢复同一 active command |
| stale CAS | 重新 load state；不得覆盖；检查是否由另一管理实例推进 |
| repository payload 损坏 | `REPOSITORY_CORRUPTED` 停止；保留原文档与审计日志，禁止手工改 payload 后继续 |
| alias 与 expected source 不一致 | `ALIAS_CONFLICT` 停止；先确认是否存在带外变更 |
| template/mapping attestation 不一致 | 停止；新建 mapping version/generation，不覆盖现有 destination |
| verification 失败 | 保持/恢复 LEGACY；保留 destination 调查，修复权威 rebuild 后以新 command 重新执行 |
| Snapshot cutover 未配置写栅栏 | `CUTOVER_FENCE_REQUIRED` 停止；不得用测试 allow guard、人工跳过或重复命令绕过 |

## 7. 证据与完成标准

每次演练/生产迁移必须保留：

- immutable manifest 与所有 command plan/result；
- repository revision、`_seq_no/_primary_term` 演进；
- template、mapping `_meta`、alias before/after；
- rebuild receipt 与完整 verification；
- SHADOW error/diff/incomplete 指标、延迟与资源快照；
- cutover、rollback rehearsal 与 `retainedSourceUntil`；
- 目标应用负责人签署。

未同时满足“权威重建、零差异 verification、真实 alias cutover/rollback 演练、回退证据”时，P5-B 只能标记为
管理内核/持久化 vertical slice 已实现，不能标记为生产迁移闭环完成。
