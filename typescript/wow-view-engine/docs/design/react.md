# React 层：钩子与控制器

`react/` 只依赖运行时与纯内核。每个钩子一节：先是签名，再是它承担的规则。默认组件与视觉规则见 [ui/README.md](ui/README.md)。

## useViewEngine

```ts
useViewEngine(options): ViewEngine
```

- 建一个并在卸载时释放；
- 需要更长生命周期由应用自建后传入。

## useViewRuntime

```ts
useViewRuntime(runtime): ViewRuntimeState | null
```

- useSyncExternalStore。（见 test/reactHooks.test.tsx「useViewRuntime」）

## useOpenView

```ts
useOpenView(engine, instanceId, scopeFilter?): { runtime | null; loading; error; scopeIssues }
```

- 拥有所开 runtime：换 id 或卸载即释放；
- runtime 在其下被释放（如实例被删除）时不再交出，按同一 id 重新打开，得到新 runtime 或 not_found；
- 注入的 scopeFilter 被拒时，`setScopeFilter` 返回的 error 级 Issue 由 `scopeIssues` 交出，宿主据此提示；
- warning 不算拒绝，条件照常生效，warning 留在 runtime 的 `issues` 里由 UI 按 warning 呈现——否则旧的、更宽的条件仍在运行却无人知晓。（见 test/reactHooks.test.tsx「useOpenView」）

## useViewList

```ts
useViewList(engine, definitionId, options?: { kind }): { items; all; preferences; permissions; defaultInstanceId; loading; error; preferencesError; reload }
```

- 给出 kind 时先按顺序排好再过滤、再解析默认，见 [management.md#列表偏好与默认视图](management.md#列表偏好与默认视图)；
- useViewManager 因此管的是过滤后的可见列表，而 move 提交的是 `all`（未过滤的完整顺序）里对调两项的结果，未列出的种类因此各守其位；
- `all` 是未经 kind 过滤、同序的全部摘要，供 move 在完整顺序里对调；
- reload 可带 `{ without?: id }`：重载期间把该 id 从留存的摘要里去掉，`preferences.defaultInstanceId` 命中它也读作 null，答案落地即恢复由 store 说了算——重载只刷新不清空，否则刚删掉的那一行会继续被列出、继续被当作默认视图，骑在默认视图上的工作台就会去重开一个刚被释放的 runtime（瞬时 not_found）；
- `useViewManager.delete` 自动带上被删的 id，宿主绕过它直接用 engine 删除时同样要带。（见 test/reactHooks.test.tsx「useViewList」「narrowed to one kind」）

## useFilterEditor

```ts
useFilterEditor(runtime): FilterController
```

- 按路径增删改、模式、清空、提交；
- Enter 提交排除 IME 与内部弹层由 UI 层处理；
- applied 读 result.own.filter（描述产出当前结果的、视图自有的那部分条件，无结果为空；路径因此仍指向 draft，badge 的删除即 clearValue(path)），Dashboard 例外：它自己没有结果（查询在各面板里），改读 state.applied.filter，即面板被要求执行时的全局条件；
- 宿主注入的作用域由 scoped 读 runtime.scopeFilter 单独描述、不可删除——读 result.config.filter 会把作用域混进同一串 badge，且 or／nor 的 draft 被包成第一个子节点后路径整体下移一层，删除会落到别的叶子上；
- 草稿超预算不清空 applied：产出结果的那份配置是先过准入才跑的，本就在预算内，摘要要一直描述它身旁的数据；
- pending／pendingCount／isPending(path) 以 state.applied 为基准（叶子比字段＋操作符＋值，分组只比 op，不比子节点）；
- pendingCount 同时走两棵树，只在 applied 里的路径也计一次（删掉一条、清空筛选同样是未应用的改动）；
- 草稿超出树预算（filter.tree.too-deep／too-many-nodes）时这三者一律为 false／0——面板本就不画它，比较也不走它（applied 不在其列，见上）；
- 超预算同样按路径认领（根路径或 `['children', …]`，与 issues 同一组），分析的指标／元素筛选与仪表盘面板筛选报的是同样的 code、只是重定址到 `['metrics', …]`／`['elements', …]`／`['panels', …]`，只看 code 会让根编辑器为一棵它不画的树关掉 pending；
- 比较本身是迭代加计数的，过深或成环的草稿返回 false 而不是爆栈；
- blocked 是落在条件上的 error 条数——包括编辑器画不出 pill 的那些（畸形节点），它们同样阻塞 Apply，只是由状态条而不是 pill 报出；
- unmarked 与 blocked 成对：blocked 数的是 pill 上那些（外加画不出 pill 的），unmarked 给出的是**没有一处标记**的那些——畸形节点、分组自身的 error，以及配置里条件以外的 error（掉了的列、不再受理的页大小）。两者合起来把"拦住视图的每一条 error"交代完，各说一次、互不重复：pill 一份，编辑器上方的状态条一份。判断由 `filter/marks.ts` 的 `unmarkedErrors(issues, tree)` 做，它只问树——哪些路径解析得到一条渲染得出的条件（谓词内部的条件也算）——所以它属于内核而不属于编辑器；unmarked 走的是整份 `state.issues` 而不是被本树收窄过的 `issues`。（见 test/reactHooks.test.tsx「useFilterEditor」「useFilterEditor pending and applied」「useFilterEditor under a host scope filter」与 test/statusStrip.test.tsx「ErrorStrip」）

## useRecordTable

```ts
useRecordTable(runtime): RecordTableController
```

- 列语义、排序、列宽列序、选择、分页；
- 无 TanStack 类型；
- layouts 为定义允许的布局，selectedRows 为当前结果中被选中的行（结果顺序），pageSizes 为可供选择的每页条数（标准档位按 runtime.limits.maxPageSize 裁剪，并并入当前值）。（见 test/reactHooks.test.tsx「useRecordTable」）

## useSaveCommands

```ts
useSaveCommands(engine, runtime): { save; saveAs; rename; delete; revert; retry; abandon; resolveConflict; can; state }
```

- can 增 revert（dirty 且已保存过）；
- state 增 blocked（pending／含 error／write 为 unknown——冲突与拒绝是确定的答复，其可选解法里含"另存一份"这类新意图，不应被禁用；需要在冲突时拦住盲目 Save 的 UI 自行查 state.write）与 lastSavedAt（最近一次真正写入成功的时间戳，按 environment.now()，新写入开始即清空）；
- retry 与 resolveConflict 兑现 `RecoveredWrite { landed; written; instance }`，landed 说"该结局已结清"，written 说"确实把这个视图存下来了"——只有正文 action 为 create／save 的恢复才算（取调用当时 state.write.payload.action）：冲突选 reload 是取服务端状态并丢弃草稿，什么也没写；
- 改名与删除的重试或覆盖确实写了 store，但写的不是屏幕上这份配置，报"View saved"等于告诉用户未保存的编辑已经安全。两者都 landed 为 true 而 written 为 false，因此都不计入 lastSavedAt；
- 只有 create／save 的重试与覆盖两者皆真；
- state 另有 hasErrors（只说草稿自身有没有 error，不说任何结局，因此调用方可以自行决定拦住哪一种未结清的写入）；
- abandon(write?) 可按传入的 WriteState 以 handle 寻址——冲突里"另存一份"之后该写入已不再由 runtime 报告，engine 却仍在其 map 里记着它。（见 test/reactHooks.test.tsx「useSaveCommands」）

## useViewManager

实现拆在 `src/react/manager/`：`outcomes.ts`（结局的归属与替换规则）、`queue.ts`（按输入打标的串行队列，React 无关）、`order.ts`（同受众相邻项与乐观顺序）、`abilities.ts`（许可投影）、`useCommandRunner.ts`（结局账本与队列的 React 一侧）；`useViewManager.ts` 只做组合。公开面只从 `/react` 入口导出。

```ts
useViewManager(engine, definitionId, list): { rename; delete; setDefault; move; canMove; outcomes; retry; abandon; resolveConflict; resubmit; canResubmit; pending; can }
```

- can 另有 anything（顺序、默认，或任一行的改名／删除中有一个可用），为假时工作台根本不把 manager 交给 ViewList——管理入口通向一屏只读的行，就是一个只能教人它通向哪儿也不去的按钮；
- 管未打开的实例：命令一律以状态兑现，成功即 list.reload()；
- outcomes 与 pending 按 engine＋definitionId 打标（同 useViewList 的"手上的答案属于哪一次请求"），换定义或换引擎即读作空，旧输入的完成不再回填；
- 命令按 ref 里的 promise 队列串行，同时至多一个写入在途，pending 恒是它的 key——队列本身也按同一组输入打标，换定义或换引擎后的命令另起一条队列立即开始，旧队列独自结清且结果无人读取，否则新列表的第一条命令会排在一个没人看的挂起写入之后；
- outcomes 按实例 id 或 PREFERENCES_KEY（`'system:preferences'`，取 store 不得签发的 `system:` 保留命名空间，避免与实例 id 撞键）记结局（ViewWriteError 的 state，handle 私有，供三个恢复动作寻址），引擎在发出前拒绝的记为 rejected 且无从重放——但拒绝绝不覆盖仍持有 handle 的结局，否则 [management.md#冲突与未知结果](management.md#冲突与未知结果) 的"unknown 未结清时拒绝新意图"反而会把该 unknown 的重试与放弃一并抹掉；
- 同一 key 的结局仍持有 handle（unknown 或 conflict）时，新意图根本不入队（直接兑现 false，并在队首再查一次——排在前面的命令可能正好把这个 key 变成 unknown），只有该 key 的 retry／abandon／resolveConflict 放行：一行只有一个结局槽位，新命令记下自己的结局就会顶掉那个 handle，被它寻址的写入从此留在 engine.pendingWrites() 里而界面上无人能重试、覆盖或放弃它——unknown 是 Engine 直接拒绝，conflict 则是 Engine 照发不误、一步之后才出同样的问题；
- rejected 不持有 handle 或只持有一个已成定论的答复，[management.md#冲突与未知结果](management.md#冲突与未知结果) 的"改正后作为新意图再保存"照常放行；
- move 在可见列表里只与**同一受众组内**的相邻项对调（列表分个人／共享两组展示，跨组对调写了偏好却什么都没动），组的首尾不写，`canMove(id, direction)` 让 UI 据此禁用箭头；
- 提交的却是 `list.all`（未经 kind 过滤的完整顺序）里把这两个 id 对调后的结果，store 一个定义只存一份 order，提交可见的那份会把工作台没画的种类整批抹掉；
- move 还在 ref 里记住已提交的乐观顺序（完整顺序与对调后的可见顺序各一份）——列表要等落地后的 reload 才追上，同一行连点两次否则会算出并提交两份相同的顺序（同组的 `[A,B,C]` 中 C 连续上移两次提交 `[A,C,B]` 与 `[C,A,B]`）——找同受众邻居走它记住的可见顺序，对调走它记住的完整顺序，两个下标必须出自同一份列表，否则第二次点会拿乐观顺序里的下标去配渲染顺序里的邻居；
- 列表身份一变（reload 已落地）即回到渲染顺序，未落地的 move 也把它撤回，而 canMove 只看渲染顺序——箭头禁不禁用要跟用户眼前的列表一致，ref 也不该在渲染里读；
- 偏好冲突按 [management.md#列表偏好与默认视图](management.md#列表偏好与默认视图) 重载后保留本次意图待再次确认（`canResubmit` 为真，按 `resubmit` 以刚读回的 revision 重新提交，它同走这条队列，且因为是一次新写入而照样受未结清守卫约束），改名／删除冲突按 [management.md#冲突与未知结果](management.md#冲突与未知结果) 推进基线后清除；
- can 取自 list.permissions，系统视图恒不可改名、删除。（见 test/viewManager.test.tsx）

## useAnalysisEditor 与 useDashboard

```ts
useAnalysisEditor(runtime): AnalysisController
useDashboard(runtime): DashboardController
```

两者的界面规则见 [ui/analysis.md](ui/analysis.md) 与 [ui/dashboard.md](ui/dashboard.md)。

## useWorkbench

```ts
useWorkbench(engine, definitionId, { kind, instanceId? }): WorkbenchController
WorkbenchController {
  list; manager; openId; choose(id); opened; runtime; state; unopenable;
  commands; filter; leave; onSaved; onRenamed; onDeleted; onRecovered
}
```

一个工作台除了自己的编辑器与结果之外的全部：哪些视图、开着哪个、它说了什么、换一个时会发生什么。三个默认工作台只在编辑器与结果上不同，装配一模一样，所以装配只写这一处。

- 列表按 `kind` 收窄后再定默认（`useViewList(engine, definitionId, { kind })`）：侧栏不提供这一页画不出的视图，默认视图也只在这些里解析。仪表盘定义本就只有 dashboard 实例，收窄对它是恒等；
- `openId` 是"此刻在开的那个"：显式选择（`choose` 写下的 pin），没有则是列表的有效默认。`choose` 一律经离开守卫，因为切换会释放当前 runtime，未保存的草稿只活在里面；
- 种类不符由 `kindMismatch` 判为 `unopenable`，与"打不开"同一个出口：一个定义同时容纳 record 与 analysis 实例，宿主仍可点名任一个，画不出的那一页要说明白，而不是在标题栏下留一片空白。`unopenable` 为真时 `runtime`／`state` 皆为 null；
- pin 指向的视图被删除后由 `react/workbench/releaseDeleted.ts` 放手：引擎随实例释放 runtime，同一 id 再开只会一直答 not_found，页面因此永远走不到还在的那个视图上。只放手**开过**的 id——宿主点名而 store 从来没有的 id 是一个要报出来的错，不是一个要导航离开的状态；
- `leave` 是无对话框的离开守卫（`react/workbench/leaveGuard.ts`）：`asking` / `request(next)` / `confirm()` / `cancel()`。`dirty` 或写入结局为 `unknown` 时才问，`confirm` 先结清（`commands.abandon()`）再走——`next` 会释放这个 runtime，结局就再没有它可依附，handle 会指向一个谁也够不着的 runtime 而 `engine.pendingWrites()` 把它留到会话结束。怎么问是宿主的事，`/ui` 用 `LeaveDialog`；
- `filter` 是这次打开的筛选编辑器，在这里建一次：外壳画已应用条件条要用它，error 条要用它的 `unmarked`，三个工作台本来也各建一个；
- 四个 `on*` 是标题栏结局的工作台语义，已经接好：存下的副本随即打开、改名留在原视图（先 pin 再 reload，否则骑在默认视图上的工作台会关掉 runtime 连草稿一起丢）、删除即移开（pin 清空，列表重读，默认视图顺位接上，或随列表一起空掉）、恢复则重读列表。（见 test/workbench.test.tsx「useWorkbench」）

宿主写自己的标记时调这一个钩子就够，规则一条也不会掉——`examples/PlainRecordWorkbench.tsx` 是那份参照。`test/architecture.test.ts` 禁止 `ui/*Workbench.tsx` 直接 import `useViewList`／`useOpenView`／`useViewManager`／`useLeaveGuard`：绕过去就是把装配重建一遍。

## RecordActionSlots

```ts
RecordActionSlots { global?; bulk?; row? }
```

- 三层业务动作的 render 槽位（react/actions.ts），由宿主传给工作台；
- 动作是代码，不进配置也不进 ViewInstance。
