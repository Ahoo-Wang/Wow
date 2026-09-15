# View Engine 首个可交付：切片与 PR 计划

**关系**：[refactor-spec.md](./refactor-spec.md) 是规范性契约，本文件是它的第一份执行计划。本文只决定"先做什么、做到什么程度、怎么验收"，不改写规范中的任何行为约束；引用规范用 §节号，引用验收场景用 §16 的编号，引用不变量用 [invariants.md](./invariants.md) 的 `INV-` 编号。
**基线**：`0ed8da1b`。
**原则**：每个 PR 独立可构建，`pnpm --filter @ahoo-wang/fetcher-view-engine test` 与根级 `pnpm test:unit` 都通过（AGENTS.md 的提交门禁），不留双实现或兼容包装（§1.8、§15.2）。合同或入口变更必须与仓库内全部调用方在同一 PR 完成。

## 1. 首个可交付的范围

首个可交付回答一个问题：**用户能否在 Record 视图上完成"筛选、调整列与排序、保存为个人视图、重新打开"这条完整任务（§15.3 闭环一），并且默认 UI 与一个结构不同的自定义组合消费同一套行为。**

| 纳入                                                                                       | 不纳入（推迟，见第 4 节）                                                            |
| ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ |
| Host 读写合同：分页目录、点查、`WriteObservation`、全部写入携带 `requestId`（§5.5、§11.8） | `services/view-host` Kotlin 服务、`contracts/view-host`、`/hosts/wow`（§2.4、§13.6） |
| 入口拆分：`.`／`/react`／`/ui`／`/hosts/memory`／`/hosts/indexeddb`（§2.2）                | Analysis、Dashboard 的行为 controller 提取（§9、§10、§11.1 对应 Hook）               |
| Filter 完整切片：协议／编辑描述／renderer 三分，`useFilterController`（§7）                | Storybook 固定状态集的 Analysis／Dashboard 部分（§11.7）                             |
| Record 完整切片：`useRecordController`、结果与展示分离、汇总与动作守卫（§8）               | §14 中尚未被本轮切片消费的全局预算项                                                 |
| Memory、IndexedDB 两个本地 Host 按新合同重写；`dev/http` 作为合同形状探针                  | SSR 预载（规范本身也排除，§6.3）                                                     |
| 闭环一的端到端验证、第二消费者、浏览器矩阵、真实包产物验证（§17）                          | 定义维护写模型（§4.4 只做定义读取与 revision 携带）                                  |

Analysis 与 Dashboard 的现有实现在本轮**原样迁入 `/ui`**，继续通过 Engine 使用新的 Host 合同工作；规范 §15.2 明确允许 `/ui` 先承载尚未完成行为提取的既有组件，前提是不反向污染 `/react`。

## 2. 切片顺序与依赖

```text
S0 文档与不变量索引
 └─ S1 Host 读写合同 ──┬─ S2 入口拆分 ──┬─ S3 Filter 切片 ─┐
                       │               └─ S4 Record 切片 ─┼─ S5 首个可交付验收
                       └──────────────────────────────────┘
```

S1 先于 S2，因为 S1 改的是合同与引擎内部，S2 只是模块搬迁；先搬再改会让 Host 文件移动两次。S2 合并后，S3 与 S4 可以由不同人并行。

## 3. 切片定义

### S0 文档与不变量索引

- 交付：`docs/refactor-spec.md`、`docs/invariants.md`、本文件。无代码变更。
- 退出：不变量索引覆盖 §1.7–§15 与 §17 的全部"必须／不能"条款，每条有节号；后续切片的验收清单从索引按节号筛选。

### S1 Host 读写合同

目标：把 §5.5 的端口表和 §11.8 的目录／点查／偏好合同落到 `ViewHost`，同时修正 §1.9 指出的三处现状：`ViewLoader.load()` 的单一等待屏障、只有 `create` 携带 `requestId`、`viewServiceContract` 的旧格式投影分支。

**PR S1-a：合同、本地 Host 与全部调用方（单个可构建提交）**

合同变更与所有按旧合同编译的代码在同一 PR 内完成：本地 Host、`dev/http`、引擎调用方、examples、测试与文档。AGENTS.md 对 view-engine 的例外条款允许破坏 API，但要求同时更新调用方、测试与文档，不留临时兼容层。

- `src/contracts/ViewHost.ts`：
  - `instance.list(definitionId, { query?, cursor?, limit?, readFence? }, signal)` 返回 `Page<ViewInstanceSummary>`，只含 `items`、`nextCursor` 和可选授权后总数；不再返回 `defaultInstanceId`。目录读取同样接受写回执中的 `readFence`，否则 create／rename／delete 后的"列表同步中"无法通过读屏障结清（§5.4、§13.6）。
  - `instance.create / save / rename / delete` 统一接收 `WriteContext { requestId; signal? }` 与各自期望版本，返回 `WriteObservation<T>`；`delete` 回执只含目标实例删除事实，删除 `ViewDeleteResult.defaultInstance`。
  - 新增 `preference.load(definitionId, { readFence? }, signal)`；`preference.saveOrder(definitionId, { scopeInstanceIds, orderedInstanceIds }, precondition, ctx)` 与 `preference.saveDefault(definitionId, instanceId | null, precondition, ctx)` 返回 `WriteObservation<PreferenceState>`，前提为 `absent` 或 `matches(revision)`。偏好聚合按租户、用户、定义划分，同一 Host 可服务多个定义，因此每个偏好端口都显式携带 `definitionId`（§13.6）。
  - 新增只读 `operation.reconcile`。
  - `ViewPermissionService` 删除 `load` 与 `refresh`，只保留同步 `getInstance`、`getDefinition` 与可选 `subscribe`；远端获取、缓存与返回顺序由 Host 或应用负责，Engine 不再发起许可请求（§13.3、INV-13-51）。
  - `definition.load(definitionId, { readFence? }, signal)` 返回携带 `id`、`revision` 的定义；`instance.load(instanceId, { readFence? }, signal)` 同样接受可选读取屏障。`readFence` 是服务返回的不透明令牌，只能提交回同服务、同主体范围的对应 load 端口，前端不解析、不比较（§5.5）。这是 `committed.visibility=pending` 能够被核对的前提。
- `src/contracts/viewServiceContract.ts`：删除 `LEGACY_VIEW_FORMATS`、`projectSupportedInstance`、`SupportedViewFormats`；错误码增加 `DEFINITION_CHANGED`、`CURSOR_EXPIRED`，`UNKNOWN_OUTCOME` 只作为 `WriteObservation.unknown` 的问题码，不再作为 Promise reject。
- `src/record/MemoryViewHost.ts`、`StatefulViewHost.ts`、`IndexedDBViewHost.ts`：按新合同实现，Memory 同步返回 `committed/visible`，IndexedDB 以事务 `oncomplete` 为提交；同键不同正文拒绝、版本冲突、`absent` 前提在两者都实现（§5.5、§13.4）。
- `dev/http/*`：按同一合同改写为合同形状探针；不承诺生产语义（§13.4）。
- 对应测试：`test/engine/*` 中涉及 Host 的用例、`test/httpViewHost.test.ts`、`scripts/verify-view-host.mjs`、`verify-indexeddb-view-host.mjs`、`verify-http-view-host.mjs`。

同一 PR 内的引擎调用方改动：

- `src/engine/ViewLoader.ts`：拆掉 `Promise.all([definition, list, permission])` 屏障，并删除其中对 `permission.load`／`refresh` 的调用。定义加载、第一页目录、个人偏好各自独立状态与失败，许可只同步读取 Host 已接受的结果；目录项只进入有界摘要缓存，不再为每一项 `createSession`；会话在显式打开时建立（§4.1、§11.8）。
- `src/engine/ViewPersistence.ts`、`InstanceWork.ts`、`ViewReload.ts`、`writeRecovery.ts`：消费四种 `WriteObservation` 结局；`committed.visibility=pending` 推进基线并保存 `readFence`；`unknown` 与 `committed_pending_receipt` 保留原 `requestId`、正文与提交证明；`rejected` 保留草稿（§5.4）。
- `src/engine/ViewManagement.ts`：删除后的选中回退与服务端默认偏好分开处理（§11.3、§13.6 删除小节）。
- `src/engine/instancePermissions.ts`：只订阅 Host 的同步 getter 与 `subscribe`；未接入或未就绪只影响管理操作可用性，直达实例不触发任何许可网络请求（§13.3、D13）。
- 状态快照新增：目录页状态、偏好状态、待核对写入列表。

同一 PR 内的消费者与文档：

- `examples/react/sales-order/host.ts`、`examples/react/catalog/*`、`examples/react/compensation/*`、`examples/core.mjs`、`dev/HttpOrderExample.tsx` 切到新合同（`verify-package.mjs` 会对 examples 做类型检查，不能留到后续 PR）。
- README 双语中 Host 接入示例同步；`skills/fetcher-view-engine/references/api.md` 中的 Host 合同、`ViewInstanceList` 与偏好签名描述同一 PR 更新（AGENTS.md 要求公共 API 变更同步 skill 参考）。

**PR S1-b：核对与目录状态补齐（不改合同）**

- `operation.reconcile` 的引擎消费：对 `unknown` 与 `committed_pending_receipt` 写入提供显式核对动作，按原 `requestId` 取得精确回执后推进基线（§5.4、§5.5）。
- `committed.visibility=pending` 时向对应 `definition.load`／`instance.load`／`preference.load` 提交 `readFence` 的有界等待与"已保存，列表同步中"状态（§11.6）。
- 目录页 `CURSOR_EXPIRED` 重开并保留已打开会话；偏好加载失败的独立状态与重试（§11.8）。

退出条件：

- S1-a 单独合并即通过包测试与根级 `pnpm test:unit`；不存在任何按旧合同编译的调用方或临时适配层。
- 类型层：不带 `requestId` 的写入调用无法编译；`list` 返回值不含实例配置；偏好端口缺少 `definitionId` 无法编译。
- 场景：A04、A05、A07、A08、A13、A17、D13、D14、D15、D16、H07、H09、H10、H11、H12、H13（Memory 与 IndexedDB 两个 Host 各跑一遍）；H01 限于实例与偏好写入，定义写入部分随定义写模型切片验收；W16 只验证前端映射。A04 覆盖显式打开改为异步点查后两个导航请求的竞争；H10 覆盖顺序与默认值同时以 `absent` 前提首次创建时只有一个成功。
- 结构：`ViewLoader` 中不再存在跨定义／目录／许可的单一 `withDeadline(Promise.all(...))`；目录加载失败不清空已打开会话。
- 不变量：`invariants.md` 中 §5.4、§5.5、§11.8、§13.1 的条款逐条勾选。

风险：`test/engine` 现有 36 个文件多数依赖旧 `list` 返回完整实例，改动面大；建议先补 §16 对应反例再改实现（§15.2 "每个切片先补当前反例"）。

### S2 入口拆分

目标：落实 §2.2 的入口表与 §2.1 的单向依赖，`/react` 变为无样式行为层。

**PR S2：新入口、架构检查与消费者切换（单个可构建提交）**

- `vite.config.ts` `lib.entry` 增加 `ui`、`hosts/memory`、`hosts/indexeddb`；`package.json` `exports` 对应新增，`sideEffects` 保持仅 CSS。
- `src/react.ts` 只保留：`useViewEngine`、读取 Hook（`useViewSession`、`useViewCapabilities`，后者从 `src/view/` 迁出）、`useOwnedViewPosition`、类型；默认 UI（`ViewPage`、`RecordView`、`AnalysisView`、`DashboardView`、`EmbeddedView`、cells、`components/ui/*`、`ViewTheme`）全部移到新的 `src/ui.ts`。
- `MemoryViewHost` 从根入口移到 `/hosts/memory`；`IndexedDBViewHost` 从 `/react` 移到 `/hosts/indexeddb`。
- `test/architecture.test.ts` 新增规则：`/react` 的运行时导入图不可到达 `components/ui`、`theme/`、`recharts`、`react-grid-layout`、`@tanstack/react-table`、`lucide-react`、默认 renderer 注册模块；根入口与 `/hosts/*` 不可到达 React。
- `scripts/verify-package.mjs`：每个入口一个独立消费探针（核心 Node、React headless、`/ui`、Memory、IndexedDB），核心声明在关闭 `skipLibCheck` 下不引入 DOM 类型（§17.1、D09）。
- `examples/`、`dev/`、`test/`、根目录 `stories/view-engine/*` 与 `stories/docs/*`（当前从 `/react` 导入 `ViewPage`、`IndexedDBViewHost`）全部导入改到目标入口；README 双语的安装与导入段落、`skills/fetcher-view-engine/SKILL.md` 与 `references/api.md` 的导入路径同一 PR 更新。按 §15.2，入口切换与全部仓库消费者更新在同一个可构建提交内完成，因此不拆成第二个 PR。

退出条件：D09、D10、G14（Memory／IndexedDB 部分）；`verify-package.mjs` 五个探针通过；架构测试新增规则通过；Storybook 构建与 `pnpm test:storybook` 通过；仓库内（含 `stories/`）不再有从 `/react` 导入默认 UI 或 Host 的消费者。

风险：`/react` 在 S3、S4 之前导出很少，属预期；不要为了"看起来完整"提前导出仍有视觉依赖的 Hook（§15.2）。

### S3 Filter 完整切片

目标：§7 全部条款；独立筛选器无需 Engine 即可使用（§2.1、E08）。

**PR S3-a：协议／编辑描述／renderer 三分**

- `src/filter/filterProtocol.ts` 只保留编译、清空、合法输入、字段与操作符能力；新增编辑描述类型（模式支持、默认值、输入解码、有效性）；renderer 绑定表移到 `src/ui` 侧的默认视觉注册。
- `resolveFilterEditor.ts` 不再静态导入日期、选择、远程控件；`builtinFilterRegistrations.tsx` 迁入 `/ui`。
- 缺少 renderer 时输出可修复问题，不伪造默认值；核心编译不受影响（D23）。

**PR S3-b：`useFilterController` 与任务部件**

- 以 `useFilterPanelState.ts`、`useFilterPanelQuery.ts`、`useFilterPanelEditors.ts` 为输入，提取 `useFilterController`：显式命名的只读状态、节点动作、模式动作、清空／撤销／提交、问题定位（§7.5）。
- 无样式部件：根区域、节点、添加入口、提交入口；Enter 提交排除 IME 组合与内部弹层。
- 独立受控值合同（§7.3）：是否受控由值属性决定，回调只通知，每次逻辑交互最多一次通知（§3.3）。
- `FilterPanel.tsx` 移入 `/ui` 并只消费 controller。

**PR S3-c：候选与日期反例补齐**

- `useRemoteFilterOptions.ts` 对照 §7.4 补齐：search 与 resolve 独立请求状态、旧回包不混入、游标去重、标签回填失败不删已选 ID、Host 报告不可访问时隐藏受限标签。
- 日期／时间／时区／重复小时偏移分别处理，草稿允许未完成文本（§7.4、B01）。

退出条件：A01、A02、B01–B05、B18、D23、E08（独立 Filter 部分）、F07（Filter 部分）；`invariants.md` §3.3、§7 条款逐条勾选；`examples/react/BuiltinFiltersExample.tsx` 改为只依赖 `/react` 加 `/ui` 的组合。

### S4 Record 完整切片

目标：§8 全部条款，加上 §11.4 自动刷新与 §11.6 状态呈现中 Record 相关的部分。

**PR S4-a：`useRecordController`**

- 绑定明确 `runtimeId`；输出结果、查询状态、分页政策、当前页选择、受保护动作；成功结果的查询配置与当前展示配置分开保留（§8.1）。
- 错误归属拆分：主表、汇总、动作、输入各自独立，不合并为一个 error 字符串。
- `RecordContent.tsx` 移入 `/ui`，只做工具栏、结果、汇总、分页的组合；修正 §1.9 提到的"回调与内部 setter 二选一"契约。
- 排序与页大小"只提交明确修改的字段"，不从闭包整份旧配置覆盖新筛选草稿（§5.2）。

**PR S4-b：表格与卡片适配**

- `useRecordTable.ts` 拆为通用控制器输出（列语义、几何数据、固定偏移）与 `/ui` 中的 TanStack 适配器；控制器不输出 `fve:*` 类名（§8.3）。
- Card 使用同一结果与动作契约。
- 列宽、列顺序提供非拖动入口，与拖动共用命令与撤销范围（§11.7、H22）。

**PR S4-c：汇总、动作守卫与刷新所有权**

- `RecordSummaries.ts`：本页汇总绑定可见成功行，全范围汇总绑定已应用筛选与指标集合；范围切换后旧汇总标注历史（§8.4）。
- `RecordActionGuard.tsx`：行操作绑定结果身份，批量操作额外绑定选择身份；业务请求前重新核对（§8.2）。
- `ViewRefreshControls.tsx`／`RecordRefreshControls.tsx`：计时器抽到位置级单一所有者，暂停条件按 §11.4；只对 Record 开放。

退出条件：B06–B11、B14、B15–B17、B20、E01、F02、F03（Record 范围）、H22（列宽与列序部分）；`invariants.md` §8、§11.4 条款逐条勾选。B14 是 S3 与 S4 的集成验收：草稿输入无效时自动刷新暂停，但显式刷新仍执行合法的已应用计划，两者不能共用同一个无效状态门禁。

### S5 首个可交付验收

- **闭环一**（§15.3）在 Memory、IndexedDB、`dev/http` 三个 Host 上各跑一遍：默认订单视图筛选待出库、调整列与排序、保存个人视图、重开恢复配置且不恢复选择与页码、数据变化后执行得到新数据并显示正确来源。
- **第二消费者**：在 `examples/react/` 新增一个只用 `/react` 的自定义组合（E05、F08），验证不依赖私有 Store 与完整页面裁剪。
- **Storybook 固定状态集**（§11.7）只覆盖 Record：首次加载、无视图、未查询、编辑未应用、查询零行、历史结果、局部失败、许可未知、冲突、保存待核对。交互回归以 `pnpm test:storybook` 执行；`verify:view-engine` 只做静态 Storybook 构建，根级 `test:unit` 也不包含这套浏览器测试。
- 浏览器矩阵：`VIEW_ENGINE_BROWSERS=chromium,firefox,webkit pnpm verify:view-engine`（不设该变量时脚本只跑 chromium，单浏览器结果不得记为矩阵通过；CI 中 `.github/workflows/build-storybook.yml` 设置了同一变量）；包产物：`node packages/view-engine/scripts/verify-package.mjs`；主题：`pnpm --filter @ahoo-wang/fetcher-view-engine test:themes`。
- README 双语：新入口、当前能力范围声明（§17.4 要求区分目标能力、已实现接口与已执行验证）。
- 清理：本轮触及范围内无双实现、无弃用别名、无失去消费者的文件（§17.3）。

退出条件：§17.3 的完成条件在 Filter 与 Record 范围内成立；每次验收记录提交、命令、退出码与环境（§17.2）。

## 4. 推迟项与重新启动的触发条件

| 推迟项                                                         | 规范位置    | 触发条件                                                                                                           |
| -------------------------------------------------------------- | ----------- | ------------------------------------------------------------------------------------------------------------------ |
| `useAnalysisController`、Analysis 编辑转换纯化                 | §9、§11.1   | S4 合并后启动，复用 S4 的结果控制器模式                                                                            |
| Dashboard 逐面板应用计划、布局事务、`useDashboardLayoutEditor` | §10、§11.1  | Analysis 切片完成；Dashboard 内容面板必须复用 S4／Analysis 的结果契约                                              |
| `services/view-host`、`contracts/view-host`、`/hosts/wow`      | §2.4、§13.6 | S1 合同在 Memory 与 `dev/http` 两个消费者上稳定且闭环一通过；届时再决定后端在本仓库还是独立仓库落地                |
| 定义维护写模型与定义重载四步                                   | §4.4、§13.6 | 与后端切片同时；本轮只要求定义携带 `revision` 并进入查询来源                                                       |
| §14 全局预算（位置数、结果字节、恢复总量）                     | §14.1       | 各切片只实现自己消费的预算项（S1：未核对写入 16 项／8 MiB、目录摘要缓存；S4：结果缓存），其余在 Dashboard 切片补齐 |
| Storybook 的 Analysis／Dashboard 状态                          | §11.7       | 对应切片启动时                                                                                                     |

## 5. 每个切片的统一检查清单

1. 先补 §16 对应反例测试，再改实现（§15.2）。
2. 从 `invariants.md` 按节号筛出条款，在 PR 描述中逐条标注"已覆盖／不适用／推迟到 Sx"。
3. 源码模式与 React Compiler 编译模式都执行（`pnpm test` 已含 `test:compiled`）。
4. 删除被替代实现与失去消费者的导出、fixture、脚本；不保留兼容包装。
5. README 双语与 examples 与代码同一 PR 更新。
6. 提交前根级 `pnpm test:unit` 通过，改动 stories 或默认 UI 时另跑 `pnpm test:storybook`（AGENTS.md）；合同或入口变更的 PR 必须包含仓库内全部调用方，单个 PR 独立可构建，不依赖后续 PR 修复编译。
