# 视图管理与持久化

视图管理覆盖实例生命周期、系统／共享／个人三种范围、列表与个人偏好、许可、冲突与未知结果、离开保护。它全部由 `ViewEngine` 的命令实现，默认 UI 与自定义组合走同一条路径，不存在第二套写入逻辑。

## 实例生命周期

| 命令                                             | store 调用                                                         | 前置检查                                                                      | 成功后                                                                                                                                                                               |
| ------------------------------------------------ | ------------------------------------------------------------------ | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `create(definitionId, { title, scope, config })` | 无                                                                 | 定义存在；标题非空；`scope` 对应的创建许可                                    | 返回 `saved = null` 的 runtime 并立即执行；不进入列表                                                                                                                                |
| `save(runtime)`，`saved = null`                  | `store.create({ definitionId, title, scope, config: draft }, ctx)` | `issues` 无 error；按 `runtime.scope` 检查 `createPersonal` 或 `createShared` | `runtime.markSaved(instance)`；列表刷新                                                                                                                                              |
| `save(runtime)`，`saved != null`                 | `store.save(id, draft, saved.revision, ctx)`                       | 无 error；`saved.scope != 'system'`；`permissions.instance(id).save`          | 发起保存的 runtime `markSaved(instance)`，`dirty = false`；其余打开该实例的 runtime 只推进基线（`moveBaseline`），各自的 `dirty` 按 draft 重算，未结清的写入状态保留给自己的恢复动作 |
| `saveAs(runtime, { title, scope })`              | `store.create(draftAsNew, ctx)`                                    | 无 error；标题非空；对应 scope 的创建许可                                     | 返回新实例并刷新列表；源 runtime 的 `saved` 与 `draft` 都不变；不自动打开，UI 提供"打开"                                                                                             |
| `rename(id, title)`                              | `store.rename(id, title, revision, ctx)`                           | `permissions.instance(id).rename`；标题非空                                   | 以返回的实例整体推进**每一个**打开该实例的 runtime 的 `saved`（含新 `revision`）并刷新列表摘要；`draft` 不受影响，其他 runtime 未结清的写入状态也不受影响                            |
| `delete(id)`                                     | `store.delete(id, revision, ctx)`                                  | `permissions.instance(id).delete`                                             | 打开该实例的每一个 runtime 都 `dispose`；列表刷新；偏好不改写，见[列表、偏好与默认视图](#列表偏好与默认视图)                                                                         |
| `open(instanceId)`                               | 代码声明的系统视图直接取自定义；其余 `store.get(id)`               | 实例可读                                                                      | 校验后建立 runtime；配置合法则立即 `apply()`                                                                                                                                         |

**保存的是配置，不是浏览状态。** `ViewInstance.config` 只含 `ViewConfig`；选择、页码、游标、结果一律不保存。重开恢复配置，并从第一页重新执行。切换 Record 或 Analysis 的布局只是一次 `edit({ layout })`，切换图型只是一次 `edit({ chart: { type } })`，各套设置都在配置中，因此切换可逆且随视图一起保存。

首次保存与另存都是 `store.create`，区别只在源 runtime 的处理：首次保存把当前 runtime 绑定到新实例，另存不改变源 runtime。改名与删除不携带配置，因此不要求当前草稿合法。

## 范围与许可

`scope` 有三个值，回答"谁配置、谁看见、谁能改"：

| scope      | 谁配置                                 | 谁看见           | 普通用户能做什么                                 | 来源                                                        |
| ---------- | -------------------------------------- | ---------------- | ------------------------------------------------ | ----------------------------------------------------------- |
| `system`   | 开发或运维；是定义的基础视图与常用视图 | 该定义的所有用户 | 打开、设为默认、排序、另存；不能改名、覆盖、删除 | 代码：`definition.views`；或服务端以 `scope: 'system'` 返回 |
| `shared`   | 有共享许可的业务用户                   | 该定义的所有用户 | 依许可决定能否覆盖、改名、删除；总可另存         | `ViewStore`                                                 |
| `personal` | 任何用户                               | 仅本人           | 全部                                             | `ViewStore`                                                 |

这三个值是**两件事的合法组合**：这个视图给谁看（受众：`personal` 或 `shared`），以及它是不是用户配置的。系统视图一定是共享视图，"个人的系统视图"不存在——把两件事合成一个值，正是为了让这句话无法写出来，而不是拆成两个字段再补一条要靠校验守住的不变式。模型给出两个具名派生：`audienceOf(scope)` 回答受众（`system` 答 `shared`），`isSystemScope(scope)` 回答出处。Dashboard 的引用约束、创建许可、只读判断与侧栏分组都走这两个派生，不各自比较字符串——散落在各处的 `scope !== 'personal'` 是同一条规则被重写了一遍，改的时候没人知道它们是一回事。创建类的入参因此是 `ViewAudience` 而不是 `Exclude<ViewScope, 'system'>`：用户只能为受众创建。

系统视图有两种来源，Engine 对它们一视同仁。**代码声明**放在 `definition.views`，随应用部署，`revision` 固定为 `'code'`，打开时不经过 store；这是"定义是代码"的自然延伸，适合每个业务对象的默认列表与常用视角。**服务端配置**由运维通过业务系统的管理入口写入，`list()` 以 `scope: 'system'` 返回；引擎不提供这条管理入口。两种来源在列表中合并，代码声明者在前。

Dashboard 的范围受其引用约束：`shared` 或 `system` Dashboard 只能引用 `shared` 或 `system` 的 Record／Analysis 实例，`validateDashboard` 在保存与另存时按目标范围检查，见 [kernels.md#dashboard-内核的规则](kernels.md#dashboard-内核的规则)。

系统视图对普通用户只读：`save`、`rename`、`delete` 在派发前被 Engine 拒绝，UI 不显示对应动作，只显示"另存"。运维修改代码声明的系统视图就是一次发版；修改服务端系统视图走业务系统的管理入口。谁能创建与修改共享视图由业务服务决定，前端只消费应用已取得的许可结果：

```ts
export interface ViewPermissions {
  createPersonal: boolean;
  createShared: boolean;
  reorder: boolean;
  setDefault: boolean;
  instance(id: string): { save: boolean; rename: boolean; delete: boolean };
}
```

`store.permissions(definitionId)` 同步返回；缺省全部允许。Engine 在每个命令派发前检查一次，并通过 `useSaveCommands().can` 暴露给 UI 决定按钮可用性。对无权修改的共享视图，用户仍可 `saveAs` 到个人范围。服务端返回 `FORBIDDEN` 时以 Issue 呈现，不伪装成配置错误。不建设角色模型，不在前端做授权推导。

## 列表、偏好与默认视图

列表、偏好、许可三者独立加载，各自有 loading 与 error，互不阻塞：列表失败不影响已打开的视图，偏好失败只使排序与默认退回服务端顺序。列表项只是摘要，不为其建立 runtime。`engine.list()` 把代码声明的系统视图与 `store.list()` 的结果合并；store 不可用时代码声明的系统视图仍然可用，这保证每个定义至少有一个可打开的视图。

```ts
export interface ViewPreferences {
  order: string[]; // 显式排序的实例 id
  defaultInstanceId: string | null;
  revision: string;
}
```

- **侧栏呈现。** 列表按受众分两组，个人在前、共享在后；系统视图落在共享组里，并以 `system` 标签标出它随定义而来。一个 data 定义同时承载记录与分析实例，所以每项以种类图标作前缀（记录／分析／仪表盘），种类由摘要的 `kind` 给出。侧栏标题取自 `definition.title`，由工作台传入并作 `nav` 的可访问名。三件事各占一个位置、互不重复：图标说种类，分组说受众，标签说出处。工作台只列出自己所画的那一种：`useViewList(engine, definitionId, { kind })` 先按 `kind` 过滤，再排序、再解析默认视图，因此 Record 工作台的侧栏与默认视图里不会出现分析实例，反之亦然（仪表盘定义只承载仪表盘，不必过滤）；宿主若显式指定了另一种的 `instanceId`，工作台以 `view.open.wrong-kind` 按「打不开」呈现，而不是留下一张空白正文。
- **行内动作的版式。** 一行的按钮分两组：排序（上移／下移）与处置（设为默认／改名／删除）各是一个 `ButtonGroup`，两组之间是 `SPACE.GROUPS`——把五个塞进同一个组只会把两种职责焊得更紧，[版式](ui/README.md#版式三块一套间距一种选项控件)那条说的是「**同一职责下**的几个动作」。按钮按许可**存在或不存在**而不是置灰（D4），各行因此带着不同的动作；要让同一个动作在每一行落在同一个横坐标，动作簇**左对齐在一个定宽槽位**里（宽度＝五个 `icon-sm` 按钮加一个组间距，148px），而不是把缺席的动作画成禁用按钮来凑满——右对齐的散按钮曾让系统行的「上移」正坐在别的行「设为默认」的位置上。改名进行中的确认／取消占同一个槽位。（见 test/viewManagerUi.test.tsx「ViewManager rows」与 stories/view-engine/RecordWorkbench.test.stories.tsx 的 `ManageViews`）
- **排序。** 工作台展示顺序为 `order` 中出现且仍存在于列表的 id，按 `order` 排列；其余按服务端返回顺序追加。`reorder(ids)` 提交**整个定义的完整顺序**与偏好 `revision`——store 一个定义只存一份 `order`。工作台按 `kind` 过滤之后只看得见其中一种，所以 `useViewList` 另交出 `all`：与 `items` 同序、未经 `kind` 过滤的全部摘要。`useViewManager.move` 在可见列表里找同受众的相邻项（跨组对调写了偏好却什么都没动），再把这两个 id 在 `all` 的顺序里对调后提交；否则记录工作台调一次序，就会把所有分析实例从 `order` 里抹掉。乐观顺序同时记住提交的完整顺序与对调后的可见顺序，连点两次才不会拿前一次的下标去配另一份列表。
- **默认视图。** `setDefault(id | null)` 只改 `defaultInstanceId`。有效默认值的解析规则：显式指定的 `instanceId` 优先；否则 `defaultInstanceId` 存在于列表则用它；否则取排序后的第一项，通常就是第一个系统视图；列表为空时显示空态并提供新建。
- **删除与偏好。** 删除实例不写偏好。读取时忽略已不存在的 id，下一次 `reorder` 或 `setDefault` 写入自然清理。
- **偏好冲突。** `setPreferences` 返回 `CONFLICT` 时重新加载偏好，保留用户本次意图并要求再次确认，不用最新 revision 静默重试。重载之后引擎已结清该冲突，原 handle 不再寻址任何写入，因此「再次确认」不是恢复动作而是一次**新写入**：`useViewManager().resubmit(key)` 只把用户那一半意图（`setDefault` 的默认值，或 `reorder` 的顺序）按刚读回的 revision 再提交一次，另一半留给对方刚写入的值；UI 在重载后把按钮换成 `label.manage.resubmit`。

## 冲突与未知结果

一个 runtime 同一时刻最多一个在途写入；不同 runtime 的写入互不阻塞。同一目标（已打开的 runtime，或按实例 id／定义 id 寻址的列表命令）上存在未结清的 `unknown` 结局时，新的写入意图被 Engine 以 `view.write.unknown-pending` 拒绝，只有该结局自身的重试放行——否则一次未确认的首次保存再点一次就是两个实例；`rejected` 与 `conflict` 是明确答案，不阻塞新意图（冲突的选项里本就包括另存）。写入结果分四类：

| 结果                                              | runtime 状态                                         | UI 提供的选择                                                                                                                                                                                                                                                                                      |
| ------------------------------------------------- | ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 成功                                              | `markSaved(instance)`                                | 无                                                                                                                                                                                                                                                                                                 |
| `CONFLICT`                                        | `write = { kind: 'conflict', remote: ViewInstance }` | **重新加载**：保存冲突丢弃草稿（`saved = draft = remote`），改名与删除冲突只推进基线（`saved = remote`，`draft` 不变）；**覆盖**：以 `remote.revision` 重放原意图；**另存**（副本落地即 `abandonWrite` 结清原冲突写入——它就是这次冲突的出口，留着只会随 runtime 一起被释放，从此无人可重试或覆盖） |
| `FORBIDDEN` / `INVALID` / `NOT_FOUND`             | `write = { kind: 'rejected', issue }`                | 显示原因，草稿保留；可修改后作为新意图再保存（保存按钮因此不因 `rejected` 而禁用，只在 pending／草稿有 error／`unknown` 未结清／`conflict` 未作答时禁用）；**知道了**：`abandonWrite` 结清它，把那行拿掉——未打开实例的那一行同样有这个按钮，`rejected` 也可能仍持着 handle                         |
| 超时、断线、`UNAVAILABLE`（请求已发出，结果未知） | `write = { kind: 'unknown', requestId, payload }`    | **重试**：同一 `requestId` 与正文再次提交，服务端去重后返回既有实例；**放弃**：清除写入状态，草稿保留                                                                                                                                                                                              |

一次写入结清时的归属规则：

- 一次写入结清时只清空 runtime 上**属于它自己**的那个结局（按 `requestId` 比对）：冲突里另存出去的那份副本是一次独立写入，结清它背后的冲突不该把副本刚报出来的 unknown 从屏幕上抹掉。
- `WriteOutcome` 因此在打开另存对话框时记住原冲突的 `WriteState`，一旦 runtime 报的不再是它（副本落地、或副本自己拒绝／未知／冲突），就按 handle 把原冲突结清——从那一刻起界面上再没有别的地址能指到它；副本在发出前就被拒绝时 runtime 报的仍是原冲突，什么也不结清。
- 同理，未打开实例的一行新命令若要顶掉一个仍持着 handle 的 `rejected` 结局，先把那个 handle 结清再占位。
- 所有恢复按钮（重试、覆盖、重新加载、再次应用、知道了）在有写入在途时禁用——同一 handle 点两次，Engine 会以 `view.write.in-flight` 拒绝第二次，而那一行会把它报成用户这次点击的失败；队列里的恢复动作在队首还会再核对一次自己读到的 handle 是否仍是该 key 的结局，被前一条命令结清了就静默跳过并兑现 false。

结局的载体与恢复动作：

- 已打开视图的结局存放在 `ViewRuntimeState.write`；所有写入方法成功时兑现各自的结果，非成功时以 `ViewWriteError` 拒绝，其 `handle` 与 `state` 直接交给调用方，不必从 `pendingWrites()` 里猜测并发命令的归属。
- 未结清的结局同时登记在 `engine.pendingWrites()` 中，并可把 `handle` 传给同一组恢复动作，因此未打开实例的列表命令（`rename`、`delete`、偏好写入）同样可以重试、覆盖与放弃。
- 这四种结局的载体就是上述两处，对应的动作是 `engine.retryWrite`、`engine.abandonWrite` 与 `engine.resolveConflict`；三种结局都保留原 `requestId` 与原 `payload`（改名保留目标标题，保存保留提交的配置，创建保留 `intent` 以区分首次保存与另存，偏好保留完整的目标 `ViewPreferences`；偏好冲突的 `remote` 是最新偏好而不是实例），覆盖与重试重放的是原意图而不是当前草稿，创建意图的重试返回新实例供 UI 提供"打开"。
- `save` 等方法在非成功结局时先写入该状态再 reject，调用方据此展示选项。
- 未知结果不是失败也不是成功。重试成功即推进基线；放弃后草稿仍在，用户可再次保存，此时生成新的 `requestId`；放弃或重试之前，同一目标不接受新的写入。
- 删除遇到 `CONFLICT` 时刷新摘要后要求再次确认。

（见 test/engine.test.ts「ViewEngine write outcomes」与「ViewEngine write re-entrancy」）

## 离开保护与导航

`dirty = draft 与 saved.config 不相等`。工作台在已打开的 runtime 之间切换不销毁 runtime，因此不提示。关闭一个 `dirty` 或存在 `unknown` 写入的 runtime 时要求确认；确认离开先结清当前 `WriteState`（`useLeaveGuard(state, { onLeave })`，工作台传 `commands.abandon`）再执行切换——runtime 随即被释放，而 Engine 会继续持有那个 handle，指着一个谁也够不到的 runtime。守卫在工作台里构造，位置在 `ViewSurface` 的 `MessagesProvider` 之外，所以工作台把自己的 `messages` 一并交给它（`useLeaveGuard(state, { messages })`），否则整页都被翻译了只有这个对话框还是英文。导航不取消在途写入：写入完成时 runtime 仍存在则更新它，已销毁则丢弃结果，服务端状态不受影响。不提供跨浏览器刷新的草稿恢复。

## 持久化端口与一致性

```ts
export interface ViewStore {
  list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]>;
  get(id: string, signal?: AbortSignal): Promise<ViewInstance>;
  create(
    input: Omit<ViewInstance, 'id' | 'revision'>,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  save(
    id: string,
    config: ViewConfig,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  rename(
    id: string,
    title: string,
    revision: string,
    ctx: WriteContext,
  ): Promise<ViewInstance>;
  delete(id: string, revision: string, ctx: WriteContext): Promise<void>;
  getPreferences(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewPreferences>;
  setPreferences(
    definitionId: string,
    prefs: ViewPreferences,
    ctx: WriteContext,
  ): Promise<ViewPreferences>;
  permissions?(definitionId: string): ViewPermissions; // 由应用预先取得，同步
}
export interface WriteContext {
  requestId: string;
  signal?: AbortSignal;
}
export class ViewStoreError extends Error {
  code: 'CONFLICT' | 'NOT_FOUND' | 'FORBIDDEN' | 'UNAVAILABLE' | 'INVALID';
  /** 冲突时服务端持有的状态；缺省时 Engine 自行回读一次。 */
  remote?: ViewInstance | ViewPreferences;
}
```

`ViewStoreError` 与其判定函数放在 `model/`：端口两侧都要说这门语言，运行时据此分类写入结局，却不能依赖任何 store 实现（[分层规则](README.md#分层与依赖规则)第 4 条要求 `runtime → store` 只取端口类型）。判定按结构而非 `instanceof`，因此第二份包副本或自行构造该形状的适配器同样被识别。

一致性策略两条：

1. **乐观版本。** 覆盖写携带期望 `revision`，不匹配抛 `CONFLICT`。Engine 把冲突暴露给 UI，用户选择"重新加载后覆盖"或"另存"。
2. **幂等 requestId。** 每个逻辑写入生成一次 requestId；超时或网络错误后的重试复用同一 requestId 与同一正文，服务端按 requestId 去重。Engine 在 UI 上把这种情况表述为"保存结果未知，可重试"，不把它当作确定失败，也不当作成功。

`ViewStore` 签发的实例 id 不得以 `system:` 开头，该前缀保留给代码声明的系统视图；Engine 合并列表时丢弃此类条目并报告 Issue，`MemoryViewStore` 在 `create` 时直接拒绝。

本包只提供一个实现：`MemoryViewStore`。它是同步 Map 加自增 revision，服务测试、示例、Storybook 与"只查询不持久化"的场景；可选的 `snapshot: { load(); save(all) }` 钩子让示例把整份数据放进 localStorage，约三十行，不是第二个实现。

不提供 IndexedDB 实现。Wow 业务应用总有后端，浏览器本地库不是保存视图的真实归宿；它需要事务内版本比较与浏览器测试矩阵，却没有一个消费者。

不在本包内提供 HTTP 实现。`ViewStore` 只有八个方法，业务应用用自己的 fetcher 实现它约一百行，HTTP 状态码到 `ViewStoreError.code` 的映射在应用侧完成。官方后端若落地，其客户端随后端合同一起发布，而不是先在前端猜一份 REST 形状。服务端的授权、可见性过滤与 requestId 去重是可信边界，前端 `permissions` 只用于按钮可用性。
