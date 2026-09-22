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

列表、偏好、许可三者独立加载，各自有 loading 与 error，互不阻塞：列表失败不影响已打开的视图，偏好失败只使排序与默认退回服务端顺序。列表项只是摘要，不为其建立 runtime。`engine.list()` 把代码声明的系统视图与 `store.list()` 的结果合并，答的是 `ViewListing { items, failed }`：store 抛错时**不再整个拒绝**，`items` 里仍是代码声明的系统视图，`failed` 是那次失败的 Issue（同时经 `onIssue` 报出），这保证每个定义至少有一个可打开的视图（F-05，2026-09-21）。`useViewList` 再守一层：**重读失败不清屏**——上一份答案（含已存视图）留在屏幕上，`error` 说明原因；从前一次失败的重读把列表清空，默认视图随之变 null，骑在默认上的工作台关掉 runtime，未保存的草稿不经离开守卫就没了。侧栏把原因说在缺的那几行该在的地方：列表为空时空态写 Issue 的句子加「重新加载列表」，还有系统视图时在它们上方一行 `status` 语气的提示（`data-slot="view-list-failed"`）加同一颗按钮；偏好加载失败以 warning 进标题栏下的状态行（排序与默认退回服务端顺序，屏幕仍可用），定义准入的 `definitionIssues` 也进状态行（error 进错误条、warning 进提示条）——此前它们只报给 `onIssue`，屏幕上一个字没有。（见 test/engine.test.ts「keeps the declared views when the store cannot list」、test/useViewList.test.tsx「keeps the previous list when a reload fails」、test/viewList.test.tsx、test/listFailure.test.tsx）

```ts
export interface ViewPreferences {
  order: string[]; // 显式排序的实例 id
  defaultInstanceId: string | null;
  revision: string;
}
```

- **侧栏呈现。** 侧栏是一条**灰底的导航列**（D12）：底色与右侧的细线由它自己画（`bg-sidebar` / `text-sidebar-foreground` / `border-sidebar-border`，五个 `--sidebar*` token 在 `styles.css` 明暗两套里各有一份，照例 defer 到 `--fve-*`），`WorkbenchShell` 外面那个 `aside` 只负责宽度——一条边只画一次，从前那根 `Separator` 因此删掉。头部一行只有定义名与图标按钮：管理视图（`manager.can.anything` 时）与折叠，并**带一条细线**——这条线与工作区标题栏底下那条**落在同一条水平线上**：两栏各有一个头，两个头收在两个高度上就读成并排的两张页面。几何是抄的不是估的：`WorkbenchShell` 的 `main` 起手 `p-4`，`view-header-block` 是一行 `min-h-10` 加 `pb-3` 加一条边，侧栏头因此是同样四个数、同样的次序（`px-3 pt-4 pb-3 border-b` 套一行 `min-h-10`），回归故事量两条线的 `bottom`。头部第三颗是 D12 的「新建视图」`+`（`label.view.new`），只在 `useWorkbench.canCreate` 时存在（D4）——定义有这一种视图、用户在某个受众可创建、工作台给了新视图的名字，三者缺一它就不在。同一条命令另有两处入口：工作区在没有任何视图可开时画的空态（`ui/workbench/NoViews.tsx`，标题、一句「新建一个，开始看数据」和一颗按钮——从前那里是一片白，读作页面坏了）与列表折起时切换器菜单里的一项；侧栏自己的空态只说那句话，不再放第三颗按钮，`+` 就在它上方四十像素处。**新视图的生命周期**（`react/useWorkbench.ts`，2026-09-21 落地，拍板 Ⅱ）：`create()` 经离开守卫，调 `engine.create(definitionId, { title, scope, config })`——标题是 `/ui` 从目录取的「新视图」（`label.view.new-title`，`WorkbenchOptions.newView.title`；`/react` 不带文案，宿主自己装配时要给一个），受众是用户能建的第一个（个人优先），配置是宿主给的模板（`RecordWorkbenchProps.template` 等三处）或该种类的默认（`defaultRecordConfig`／`defaultAnalysisConfig`／`emptyDashboardConfig`，`react/workbench/newView.ts` 按种类挑，模板种类不对就当没给）。新视图的 runtime 由工作台自己拿着而不是按 id 打开：`opened` 直接就是它，`useOpenView` 这时开的是 `null`（原来开着的那个随之关闭），侧栏没有当前项，标题栏标「尚未保存」，编辑带默认展开（外壳本就对 `saved === null` 这样做）。**没改过的新视图切走时不问**：runtime 自己把 `saved === null` 记为 dirty（丢了就是全丢），但草稿仍是打开时那一份就没有用户的东西可丢，守卫读的是 `draft !== 打开时的 draft`；改过才问。**第一次保存是创建**：`SaveActions` 在 `commands.state.isNew` 时让主按钮打开另存那张表（`SaveAsDialog` 的 `intent: 'first'`——标题「保存视图」、说明「起个名字，并说明给谁看」、标题栏预填当前名字而不是「{title} 副本」、提交按钮写「保存」），走 `commands.saveAs`；存下的实例经 `onSaved` 按 id 打开、进列表，新视图的 runtime 随之关闭——与另存的副本一条路，焦点也同样落到新标题上。菜单里这时没有「另存为」：保存就是创建，再给一条创建同一视图的路是同一个问题问两遍。宿主通过 `instanceId` 路由时，新视图没有 id，所以 `onInstanceChange` 在它存下之前什么也不说；宿主此时推入别的 id 照走守卫。阶段 2「记录与分析同一列表切换」时 `+` 变成选种类，命令签名照此预留。（见 test/workbench.test.tsx「a view made from nothing」、test/newView.test.tsx、浏览器故事「Record 工作台/回归」的 `NewView`）列表按受众分两组，个人在前、共享在后，每组一个**看得见的分区标题**（我的视图／共享视图，`label.scope.group.*`）——受众图标随之去掉，字说过一遍就够；系统视图落在共享组里，名字后面跟一个灰字 `system`（`label.scope.tag.system`）而不是行尾一颗徽章，行尾留给星。一个 data 定义同时承载记录与分析实例，所以每项以种类图标作前缀（记录／分析／仪表盘），种类由摘要的 `kind` 给出。默认视图在项末画一颗实心 ★（`text-primary fill-current`，另附一句 `sr-only` 的「默认」），读的是 `list.preferences?.defaultInstanceId`——与管理器那颗星同一份状态，两块屏幕因此说不出两种话；星只画不按，设默认是管理器的事，一行点下去有两种含义就是两种含义。当前项是**工作区的白底 + 左侧 2px `primary` 条 + 中等字重**，悬停是这条列自己的另一档灰（`hover:bg-sidebar-accent`，盖掉 ghost 的 `muted`——在这块底色上 `muted` 就是底色）；这几档颜色住在 `ui/variants.tsx` 的 `SidebarItem` 里（D16-8：vendored `Button` 不动，调用处只说这一项是不是当前项）：从前四个状态共用一档 3% 灰，当前项与悬停项同色，列表根本没有「你在这里」；一根条是**另一种**记号，主题怎么调也并不进旁边那块填充。侧栏标题取自 `definition.title`，由工作台传入并作 `nav` 的可访问名。工作台只列出自己所画的那一种：`useViewList(engine, definitionId, { kind })` 先按 `kind` 过滤，再排序、再解析默认视图，因此 Record 工作台的侧栏与默认视图里不会出现分析实例，反之亦然（仪表盘定义只承载仪表盘，不必过滤）；宿主若显式指定了另一种的 `instanceId`，工作台以 `view.open.wrong-kind` 按「打不开」呈现，而不是留下一张空白正文。
- **行内动作的版式。** 一行的两件职责分在两头：**这个视图排在哪儿**是行首的拖动手柄（`ui/DragHandle.tsx`，与列设置、排序编辑器同一个组件——`GripVerticalIcon`、方向键与拖动途中静音都在那一份里；这里只给它名字 `label.manage.drag`「Reorder {title}」、行尾那几格用的 `icon-sm` 尺寸与禁用的理由），**这个视图要怎么处置**（设为默认／改名／删除）是行尾一个 `ButtonGroup`——把它们塞进同一个组只会把两种职责焊得更紧，[版式](ui/README.md#版式三块一套间距一种选项控件)那条说的是「**同一职责下**的几个动作」。按钮按许可**存在或不存在**而不是置灰（D4），各行因此带着不同的动作；要让同一个动作在每一行落在同一个横坐标，动作簇**左对齐在一个定宽槽位**里（宽度＝三个 `icon-sm` 按钮，84px——上移／下移退场之后重新量的），而不是把缺席的动作画成禁用按钮来凑满——右对齐的散按钮曾让系统行的那几枚图标正坐在别的行另一个动作的位置上。手柄是列表级许可（`can.reorder`），所以要么每一行都有、要么一行都没有，各行因此照样对齐。改名进行中的确认／取消占同一个槽位，手柄在改名与写入在途时禁用（这是「此刻正忙」而不是许可，所以是置灰而不是缺席）。**对话框打开时焦点给标题**（`initialFocus`）：落在第一个可聚焦元素上就是落在一次写入上——先是星星，手柄领头之后就是手柄——而标题才是读者开始读的地方。（见 test/viewManagerUi.test.tsx「ViewManager rows」与 stories/view-engine/RecordWorkbench.test.stories.tsx 的 `ManageViews`）
- **改名的键盘与关掉对话框之后的焦点。** 行内改名的输入框自己接住两个键：Enter 走 ✓ 那一条路（同样 trim、同样拒绝空名），Escape 取消改名并 `stopPropagation()`——管理器是个对话框，它的 dismiss 挂在 `document` 上，让 Escape 冒上去就等于一个键撤销两件事，只有一件是用户要的；而删除确认关掉之后焦点交给管理器标题（`DialogTitle`，`tabIndex={-1}`），因为确认它的那个按钮所在的行可能已经被这次删除删掉了，焦点还给一个离开了文档的元素就是还给 `<body>`——那份确认本身是一个 `AlertDialog`（`ui/DeleteDialog.tsx`），点管理器上别处关不掉它，只能在「保留」与「删除」之间选一个；另存创建视图之后焦点交给新视图的标题 `h2`（`data-slot="view-title"`），由 `WorkbenchShell` 持着 ref 与一个意图，等新 runtime 打开、标题画出来的那一帧再花掉——落地那一刻旧 runtime 正被释放，标题栏整个不在。（见 test/viewManagerUi.test.tsx、test/saveActions.test.tsx）
- **排序。** 工作台展示顺序为 `order` 中出现且仍存在于列表的 id，按 `order` 排列；其余按服务端返回顺序追加。`reorder(ids)` 提交**整个定义的完整顺序**与偏好 `revision`——store 一个定义只存一份 `order`。工作台按 `kind` 过滤之后只看得见其中一种，所以 `useViewList` 另交出 `all`：与 `items` 同序、未经 `kind` 过滤的全部摘要。
  - **用拖的，不是一格一格点。** 顺序由行首的手柄拖出来，用的是列设置那同一套（`@dnd-kit/react` + `@dnd-kit/dom`，`OptimisticSortingPlugin` 关掉——它会在指针移动时就改 DOM，落下那一刻读到的下标正好是过期的）。**键盘等价物在同一枚手柄上**：方向键上下各移一位，空格拿起、方向键移动、再按空格放下、Esc 取消（库的键盘传感器），读屏措辞全走目录（`label.manage.instructions`／`picked`／`cancelled`，落位由对话框自己播报一次 `label.manage.moved`，落库之后才说——没落地的移动不该说它去了哪儿）。系统视图和别的行一样能拖：排序是用户自己的偏好，不是对那个视图的写入。
  - **拖动只在同一受众组内生效。** 个人与共享各是一个 sortable group，另一组的行根本不是放置目标；两边的列表都把个人画在共享之上，跨组落下只会写一次偏好、花一个 revision，而屏幕上什么都没动。这一条是 `ui/manage/drag.ts` 的 `managerDrop` 加在共用守卫之上的那一层：「算不算一次放下」（没取消、两端都在、不是落回原处）是三处共用的 `ui/dragDrop.ts` 的 `dropped()`，受众相同才是管理器自己要问的。
  - **落下提交整份顺序。** 控制器的命令是 `moveTo(id, index)`，`index` 按**该行自己那一组、用户看得见的那几行**计数（拖动读放置目标那一行的位置，方向键读自己的位置加一），然后在 `all` 的完整顺序里把这一组占的那几个位置按移动后的组序重写——没参与的 id（别的种类、另一受众）原地不动；否则记录工作台调一次序，就会把所有分析实例从 `order` 里抹掉。
  - **失败不悄悄回滚。** 屏幕在落库之前本来就没动（乐观插件关着，列表等落地后的 reload 才追上），所以被拒的那一次没有什么要回滚的——它照 [冲突与未知结果](#冲突与未知结果) 那条路在列表那一行（`PREFERENCES_KEY`）报出来，顺序仍是库里存的那一份。
  - 乐观顺序同时记住提交的完整顺序与移动后的可见顺序，连动两次才不会拿前一次的下标去配另一份列表——所以位置要在**下手那一刻**问（`manager.placeOf(id)`），而不是从这一次渲染上读：排队中还没落地的那一次移动，屏幕上还看不见。
- **默认视图。** `setDefault(id | null)` 只改 `defaultInstanceId`。有效默认值的解析规则：显式指定的 `instanceId` 优先；否则 `defaultInstanceId` 存在于列表则用它；否则取排序后的第一项，通常就是第一个系统视图；列表为空时工作区显示空态并提供新建（见上）。
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

`conflict`、`unknown`、`rejected` 这三条路径在 Storybook 里各有一条能上手操作的故事——已打开视图的 `WriteOutcome`、管理器行内的结局、删除冲突的二次确认，夹具存储 `stories/view-engine/outcomesStore.ts` 按需给下一次写入安排结局（冲突由一次抢先落库的真实写入制造，所以远端配置与 revision 都是真的）。

## 离开保护与导航

`dirty = draft 与 saved.config 不相等`。工作台在已打开的 runtime 之间切换不销毁 runtime，因此不提示。关闭一个 `dirty` 或存在 `unknown` 写入的 runtime 时要求确认；确认离开先结清当前 `WriteState`（`useLeaveGuard(state, { onLeave })`，工作台传 `commands.abandon`）再执行切换——runtime 随即被释放，而 Engine 会继续持有那个 handle，指着一个谁也够不到的 runtime。守卫在工作台里构造，位置在 `ViewSurface` 的 `MessagesProvider` 之外，所以工作台把自己的 `messages` 一并交给它（`useLeaveGuard(state, { messages })`），否则整页都被翻译了只有这个对话框还是英文。导航不取消在途写入：写入完成时 runtime 仍存在则更新它，已销毁则丢弃结果，服务端状态不受影响。不提供跨浏览器刷新的草稿恢复。

## 持久化端口与一致性

```ts
export interface ViewStore {
  list(
    definitionId: string,
    signal?: AbortSignal,
  ): Promise<ViewInstanceSummary[]>; // store 的；engine.list 包成 ViewListing { items, failed }
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
  /** 实例写入冲突时服务端持有的那个实例。 */
  instance?: ViewInstance;
  /** 偏好写入冲突时服务端持有的那份偏好。 */
  preferences?: ViewPreferences;
}
```

**冲突状态分两个成员，不是一个联合。** 一个 `remote?: ViewInstance | ViewPreferences` 编译得过，代价是每个用处都要 cast 一次：偏好冲突带回一个实例照样一路读作偏好。store 只填自己这次写的那一个；Engine 按 `payload.action` 读对应的那个成员，填错的那个读作没填，于是回落到自己回读一次（`store.getPreferences` 或 `store.get`），而不是被当真。构造函数第三个参数因此是 `{ instance }` 或 `{ preferences }`。（见 test/store.test.ts、test/writeLedger.test.ts「ignores a conflict state the write was not about」）

`ViewStoreError` 与其判定函数放在 `model/`：端口两侧都要说这门语言，运行时据此分类写入结局，却不能依赖任何 store 实现（[分层规则](README.md#分层与依赖规则)第 4 条要求 `runtime → store` 只取端口类型）。判定按结构而非 `instanceof`，因此第二份包副本或自行构造该形状的适配器同样被识别。

一致性策略两条：

1. **乐观版本。** 覆盖写携带期望 `revision`，不匹配抛 `CONFLICT`。Engine 把冲突暴露给 UI，用户选择"重新加载后覆盖"或"另存"。
2. **幂等 requestId。** 每个逻辑写入生成一次 requestId；超时或网络错误后的重试复用同一 requestId 与同一正文，服务端按 requestId 去重。Engine 在 UI 上把这种情况表述为"保存结果未知，可重试"，不把它当作确定失败，也不当作成功。

`ViewStore` 签发的实例 id 不得以 `system:` 开头，该前缀保留给代码声明的系统视图；Engine 合并列表时丢弃此类条目并报告 Issue，`MemoryViewStore` 在 `create` 时直接拒绝。

本包只提供一个实现：`MemoryViewStore`。它是同步 Map 加自增 revision，服务测试、示例、Storybook 与"只查询不持久化"的场景；可选的 `snapshot: { load(); save(all) }` 钩子让示例把整份数据放进 localStorage，约三十行，不是第二个实现。

不提供 IndexedDB 实现。Wow 业务应用总有后端，浏览器本地库不是保存视图的真实归宿；它需要事务内版本比较与浏览器测试矩阵，却没有一个消费者。

`getPreferences` 对**从未排序也从未设过默认**的定义答 `emptyPreferences()`（revision `'0'`）而不是抛错：这个 revision 就是第一次 `setPreferences` 的 `If-Match`，两个实现必须给同一个，否则同一份宿主代码对两个 store 发出的第一个请求就不一样。`MemoryViewStore` 一直如此，`examples/FetcherViewStore.ts` 把服务端的 404 映射成它。

`permissions` 是端口里唯一同步的方法——Engine 每次派发命令前都要问一次，问不起一个来回。HTTP 实现因此先取后答：`examples/FetcherViewStore.ts` 用 `loadPermissions(definitionId)` 取一次并存下，`permissions()` 从存下的那份同步作答；**没取过的定义答"全部允许"**，与端口对"根本没实现 `permissions` 的 store"的缺省一致——服务端才是可信边界，这里只决定按钮亮不亮。服务端答复里没说到的那一项同样读作允许（沉默不是拒绝），只把它明确说到的那些收窄；答复没点名的实例（包括这次答复之后新建的）按 `instanceDefault` 算。

不在本包内提供 HTTP 实现。`ViewStore` 只有八个方法，业务应用用自己的 fetcher 实现它约一百行，HTTP 状态码到 `ViewStoreError.code` 的映射在应用侧完成。官方后端若落地，其客户端随后端合同一起发布，而不是先在前端猜一份 REST 形状。服务端的授权、可见性过滤与 requestId 去重是可信边界，前端 `permissions` 只用于按钮可用性。
