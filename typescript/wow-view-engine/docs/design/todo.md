# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 重构（小步，每步一个 PR，零行为变化）

- **R2 拆 `src/react/useViewManager.ts`**（804 行）——为什么：结局、队列与顺序三件事挤在一个文件里，[react.md#useviewmanager](react.md#useviewmanager) 也因此是全篇最长的一节。判据：拆为 `react/manager/{outcomes,queue,order}.ts` 加一层组合，公开面不变，`test/viewManager.test.tsx` 原样通过。落点：`src/react/`。
- **R3 抽 `useWorkbench` 与 `WorkbenchShell`**——为什么：三个工作台各自把 list + open + releaseDeleted + manager + leaveGuard + wrong-kind 装配一遍。判据：`/react` 出 `useWorkbench`，`/ui` 出 `WorkbenchShell`，三个工作台变薄，`examples/PlainRecordWorkbench.tsx` 改用 `useWorkbench`；`unmarkedErrors` 从 `ui/StatusStrip.tsx` 移到 `useFilterEditor`（暴露 `unmarked`，与 `blocked` 成对）；三个工作台的测试不变。落点：`src/react/`、`src/ui/`、[ui/README.md#工作台骨架](ui/README.md#工作台骨架)。
- **R4 拆过长的 UI 文件**——为什么：同一个文件里既有布局又有分支，改一处要重读全部。判据：`ViewManager.tsx` 拆为 Row / DeleteDialog / OutcomeActions（与 `WriteOutcome` 共用"结局→按钮"组件），`WriteOutcome.tsx` 抽 ConflictConfirm，`FilterPanel.tsx` 拆为 GroupBlock / ConditionPill / FilterActions，`FilterValueEditor.tsx` 按 `EditorDescriptor.input` 分文件；行为与测试不变。落点：`src/ui/`。
- **R5 写入结局模型去重**——为什么：`useSaveCommands` 与 `useViewManager` 各写了一遍"结局怎么结清、哪种恢复算写了视图、什么时候禁用"。判据：`src/react/writes.ts` 给出 `settle` / `RecoveredWrite` / `savesView` / `blockedBy`，两个钩子共用，两边测试不变。落点：`src/react/`、[management.md#冲突与未知结果](management.md#冲突与未知结果)。
- **R6 文案目录分文件 + `MessageKey` 类型**——为什么：一份平铺的目录看不出哪些键还活着。判据：按前缀分文件并导出 `MessageKey`；同时删除死键 `label.save.rename`、`label.save.delete`、`label.rename.heading`、`label.rename.description`、`label.unknown.consequence`；`test/messages.test.tsx` 仍全绿。落点：`src/ui/messages.ts`、[ui/README.md#措辞与-messagesprovider](ui/README.md#措辞与-messagesprovider)。
- **R7 `runtime/viewEngine.ts` 抽 `writeLedger.ts`**——为什么：注册表与写入账本两件事同在一个类里。判据：`writes` / `owners` / `pendingWrites` / `retry` / `abandon` / `resolveConflict` 移入 `writeLedger.ts`，`ViewEngine` 公开面不变。落点：`src/runtime/`、[runtime.md#viewengine](runtime.md#viewengine)。
- **守护**——为什么：拆完要拦住它长回去。判据：ESLint `max-lines` 绊线覆盖 `src`（不含 vendored 的 `ui/components`、`ui/lib`）；`test/architecture.test.ts` 禁止 `*Workbench.tsx` 绕过 `useWorkbench` 直接 import 装配用的钩子。落点：`eslint.config.js`、`test/architecture.test.ts`。

## 功能（legacy 形态）

- **侧栏可折叠**——为什么：legacy 有，现在没有，窄屏上侧栏占掉结果的宽度。判据：折叠时标题栏最左出现展开按钮 + 定义标题 + 视图切换下拉（按受众分组、当前项打勾、系统标签、底部"管理视图"项）；筛选带增加"撤销筛选修改"（草稿筛选退回已应用）；对照 `view-engine-legacy` 截图。落点：`src/ui/ViewList.tsx`、`src/ui/ViewHeader.tsx`、[ui/README.md#工作台骨架](ui/README.md#工作台骨架)。
- **写入结局的 Storybook 故事**——为什么：conflict / unknown / rejected 三条路径只有单测走过，改 UI 时没人看得见它们。判据：`WriteOutcome`、管理器行内结局、删除冲突二次确认各有故事，夹具的假存储能注入 `CONFLICT` 与 `UNAVAILABLE`。落点：`stories/`、`test/fixtures/`、[management.md#冲突与未知结果](management.md#冲突与未知结果)。

## 小修

- **`ViewHeader` 的视图名改为标题元素**——为什么：现在它只是一段文本，读屏器跳不到。判据：渲染为 `h2`（或由宿主定级），`main` 以它 `aria-labelledby`；`test/accessibility.test.tsx` 覆盖。落点：`src/ui/ViewHeader.tsx`。
- **删除后重载列表的合同容易漏**——为什么：宿主直接经引擎删除实例时必须调用 `list.reload({ without: id })`，这条合同写在文档里而不是类型里。判据：评估改为引擎侧通知（架构决定，先记录，不急着改）；结论写进 [decisions.md](decisions.md)。落点：[react.md#useviewlist](react.md#useviewlist)。
