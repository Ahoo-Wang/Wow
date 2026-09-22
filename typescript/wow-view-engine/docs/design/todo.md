# TODO

规则，先读再加：

- 只记**已经决定、但还没做**的事。尚无结论的产品问题不在这里，在 [decisions.md#搁置待议](decisions.md#搁置待议)。
- 每条都要写清 **为什么** / **做完的判据** / **落点**（design 里的哪一页，或哪个文件）。三者缺一就不是一条 TODO，是一句抱怨。
- 做完就**删掉**这一条，不打勾、不留归档。历史在 git 里。
- 改行为之前先看这里有没有对应项；有就接着做，别另起一条。

## 本轮范围：全局功能与记录视图推进到生产级

用户定下的范围只有两块——**全局功能（外壳）** 与 **记录视图**。Analysis 编辑器形态、Dashboard 编排不在本轮，它们的未决问题在 [decisions.md#搁置待议](decisions.md#搁置待议)。

每条的判据以这条基线读，**但只取适用的那几项**：一条只补回归的条目不必造文案，一条只看不改的条目没有并发路径。基线是——新出现的行为，其错误、空、权限、并发路径各有定义也有测试；新出现的界面键盘可达并过 axe；新出现的文案中英齐全；新出现的交互有故事既能手动操作也有回归；design 对应页与本页同步；全门绿。哪几项适用，由各条自己的判据说了算。

顺序曾是：打磨清单（先看后改）→ 筛选 → ErrorBoundary → 列宽・隐藏字段排序・指针拖动回归 → 写入结局故事・视图管理拖动排序——打磨排在新功能之前：先把已经有的做对，再加没有的。这些已全部落地，到哪了见 [progress.md](progress.md)；本页此刻没有条目。

## 布局重构（D12／D13，按这个顺序开 PR）

结构在 [decisions.md#d12](decisions.md#d12-一屏七块每块只回答一个问题) 定了，对比页第 17 版是定稿。每条的判据除下面写的，还有共同的一份：`test/accessibility.test.tsx` 过 axe；jsdom 钉结构（哪些槽位、什么条件下存在）、浏览器故事钉几何与层叠色；中英文案齐全；`docs/design/ui/README.md` 对应节同步；全门绿。

## 重构（小步，每步一个 PR，零行为变化）

## `max-lines` 存量豁免（拆到阈值以内就删掉 override）

绊线已落地：`src` 上限 500 代码行、`test` 上限 1200，只数代码行（跳过空行与注释）；vendored 的 `ui/components`、`ui/lib` 与纯文案目录 `ui/messages/` 不在管辖内。

当下一条 override 也没有：绊线管辖内的文件全部在阈值以内，`eslint.config.js` 里的豁免表空着。**新增一条 override 必须同时在这里新增一条 TODO**，否则绊线就成了摆设；**上限不钉死在实测值，而是实测代码行 × 1.1 向上取到十位**——改个 bug 多两行不该把 CI 打红，但 10% 攒不回一个新主题，拆完一轮要重新实测、重新收紧这些数字。

（架构测试里「`*Workbench.tsx` 不得绕过 `useWorkbench` 直接 import 装配用的钩子」那一条已随 R3 落地。）

## 全局功能（外壳）

## 打磨（先看后改，不凭想象改）

这一组来自 2026-09-20 在 Storybook 上对合并后的 `main`（ddeda440）逐屏过的一遍，条件按九项走：默认、展开筛选、空、加载、失败、窄屏、暗色、中文、长标题与多列；三个工作台加视图管理器、另存对话框，以及各自铺满屏幕的样子，每屏都跑了 axe。**先看后改这条对下面每一条同样成立**——每条都记了当时量到的数，改之前先自己再看一眼那一屏，数会随别的改动变，条目不会自己失效。量出来是缺陷而不是难看的，不在这一组里：它们分别记在[全局功能（外壳）](#全局功能外壳)与[小修](#小修)。

## 打磨（第二轮：2026-09-21 的评审）

这一组来自 2026-09-21 对 `main`（d987fb99，#1573／#1574 之后）做的一次以用户体验为目标的评审：Storybook 里 Record 工作台全部 17 个故事各跑 1280（亮／暗）、768、375 三档，另按真实任务走了一遍（打开 → 读结果 → 加条件 → 应用 → 保存 → 另存 → 切视图 → 管理器改名／排序／设默认／删除 → 列／排序／每页条数 → 选行 + 批量 → 铺满 → 查询失败恢复 → zh-CN → 窄宿主）。数字来自 `getComputedStyle`／`getBoundingClientRect`，对比度按 WCAG 公式由 oklch 换算。**先看后改**对这一组同样成立。评审时 #1575–#1578 还是开着的 PR，它们管的不重复记。评审中量出来是**缺陷**而不是难看的，记在最前面。

### 缺陷（先做）

### 打磨

### 视觉

## 打磨（第三轮：2026-09-21 宽表与 `EmbeddedView` 逐屏）

这一组来自补齐那两块夹具之后的一次逐屏：`stories/view-engine/` 新增的「宽表 ·
20 列 50 行」（`waybillsDefinition`，21 个字段、保存的视图摆 20 列、50 行，外加
宿主的操作列）与 `EmbeddedView.stories.tsx`（默认／宿主收窄／收窄被拒／空／失
败／加载／铺满／分析／汇总退回本页），在 Chromium 上按 1280×900 与 420×860 两
档各走了一遍（基线 `main` 的 bad9c676，#1616 之后），数字全部由
`getComputedStyle` 与 `getBoundingClientRect` 量出。
**先看后改**对这一组同样成立：量到的数会随别的改动变，改之前自己再看一眼那一
屏。走下来对的那些不记在这里（表头与两行汇总在纵滚 2000px 后仍钉在
`areaTop`=523；左右两条冻结边在横滚到两头时都在、中间列始终没有边；中间列
用键盘拖宽 73 → 201px 后 `scrollWidth` 1939 → 2067 且冻结列没动，标题旁立刻
出现「已修改」；导出窗口在 20 列上是 365×294、列清单四行读完不滚；排序弹层
列齐三条并只提供剩下四个可排序字段；卡片布局 3 × 214px 无横向溢出）。

## 阶段 1 审计（2026-09-21）：布局与记录视图的 review

这一组来自 2026-09-21 对 `main`（#1660 之后）做的阶段 1 审计——三份报告（体验与视觉、功能与宿主集成、架构与测试）合成一张清单，十二个产品拍板已记为 [decisions.md#D18](decisions.md#d18-阶段一审计的十二条裁定2026-09-21)。编号沿用那张清单（F 缺陷、P 打磨、A 架构）；每条的判据以本页开头的基线读，只取适用的几项。**先看后改**对这一组同样成立。已合并的（A-01、F-01、F-02、F-03）与已开 PR 的（F-08／09／10／12／13／15／16／17／18）不在这里，进度见 [progress.md](progress.md)。

### 缺陷（先做）

- **F-04 筛选／reference 与远程候选**：`reference` 值是 `{items:[{id,label}]}`，UI 直接交给 `OptionValue`／`TextValue`，选一个候选就把叶子写成数组、Apply 与 Save 双双被挡；已存 label 读不出来；运行时定义好的 `OptionSource`（search／resolve／cursor／AbortSignal）在 UI 里一次都没被调用。判据：`RemoteValue` 做值适配并走 `engine.resolveOptions`：`Combobox multiple` + 防抖搜索 + 加载更多 + 加载／空／失败三态；`optionsFor` 保留为全量近路；`OptionValue` 换 `Combobox multiple`；UI 回归与故事（cursor 源）。落点：`src/ui/filter/inputs/`、`src/ui/FilterValueEditor.tsx`、[ui/README.md#filterpanel-的布局](ui/README.md#filterpanel-的布局)。
- **F-14 状态行与结果块**：配置跑不起来时画一圈空外框只装工具栏且导出仍可按；只有一条 error 也折叠成「还有 1 条」且无修法出口；失败条右缘被裁 22px；失败时红条在工具栏之上，与 D12「工具栏是第一行」矛盾。判据：无结果且无在途时不画结果块；单条 error 直接说 + 「打开列设置」动作；`LineAlert` 宽按容器；strips 槽顺序改为工具栏在上。落点：`src/ui/workbench/ResultBlock.tsx`、`src/ui/alerts.tsx`、[ui/record.md](ui/record.md)。

### 打磨

- **P-02 表头／列宽把手**：每列一个 Tab 站，20 列就是 20 个。判据：把手 `tabindex=-1`，表头 roving 组 + Alt+←/→。落点：`src/ui/record/ColumnResizer.tsx`、`SortableHeader.tsx`。
- **P-04 多值录入框**：chips 后的录入框无 placeholder、无 `inputMode`；文档说的「添加按钮」不存在。判据：placeholder + `inputMode=decimal`；文档删掉按钮那半句。落点：`src/ui/filter/inputs/chips.tsx`。
- **P-05 工具栏左端**：「清除选择」是 ghost，读起来像标签不像按钮。判据：outline，或做成徽章旁的 ✕ 图标按钮。落点：`src/ui/ResultToolbar.tsx`。
- **P-07 筛选托盘／高级**：高级模式托盘约 620px 高，800×900 上结果整个掉出首屏。判据：封顶（`max-h-[40vh] overflow-auto`）或多条件时默认折起内层分组。落点：`src/ui/FilterPanel.tsx`、[ui/README.md#filterpanel-的布局](ui/README.md#filterpanel-的布局)。
- **P-13 打开视图时**：整页只有一条 `h-8` 骨架。判据：标题栏 + 结果块骨架，结构与打开后一致。落点：`src/ui/WorkbenchShell.tsx`。
- **P-15 筛选／取反**（拍板 Ⅶ）：简单模式写不出「不在这段时间内」。判据：pill 上的取反开关，写成 `nor`。落点：`src/ui/filter/ConditionPill.tsx`、`src/filter/`、[ui/README.md#filterpanel-的布局](ui/README.md#filterpanel-的布局)。

### 架构与测试（零行为变化，防腐化）

- **A-04 同名不同义**：两个 `ACTIONS_COLUMN`、两个 `ColumnPin`；`AGENTS.md` 的目录树漏了 62 个文件含三个整目录。判据：改名 + 重新生成目录树（脚本化）。落点：`src/ui/record/`、`src/ui/columns/`、`AGENTS.md`。
- **A-07 零行为小收口批次**：三处 `hasResult`、三份 `GROUPS`、三处路径谓词、四次重建 `RecordColumn`、四处 `notify`、三段同样的 Badge、六个只自用的 export、五条过时／说反的注释、死 eslint 配置、三处指向已删测试文件的「见 test/…」。判据：合成一到两个 PR，全程不该有屏幕差异。落点：分散，见审计报告。
- **A-09 jsdom 里的 className 断言（159 处）**：断言 Tailwind 类名证明不了回归，且会在把颜色搬进 cva 时整片变红；表格上色配方在两个文件重复五次。判据：先把断言改成 `data-slot`／状态／可达名；再抽 `StickyTableLayer` 等封装；文档承认第四落点。顺序不能反。落点：`test/`、`src/ui/record/`。
- **A-10 覆盖洞与无合同测试**：`RecordCards` 78% 全包最低、`ColumnResizer` 指针拖动整段未覆盖、`useWorkbench.onRecovered` 从未被调用；`ViewSwitcher` 与 `ExportDialog` 没有直接测试。判据：补五处。落点：`test/`。
- **A-11 测试质量**：14 处 `waitFor(async …)` 每 50ms 重发 store 请求；七个测试文件在 1200 行绊线的 83–92%；文案目录只查正向，两条死 key。判据：整改 waitFor；拆 `recordTable`／`viewManagerUi`；反向 key 检查。落点：`test/`。

## 小修
