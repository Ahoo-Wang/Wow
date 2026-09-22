# 进度：本轮到哪了

**日期**：2026-09-21（收尾）
**读法**：这一页只记「已经落地的」与「下一步从哪起」。要做的事在 [todo.md](todo.md)（完成即删），产品决定在 [decisions.md](decisions.md)。本页在每个暂停点重写，不追加。

## 已落地

**本轮范围**（[todo.md#本轮范围](todo.md#本轮范围全局功能与记录视图推进到生产级)）内的项已全部落地，`todo.md` 的存量条目为空，`max-lines` 豁免表为空，没有开着的 PR。

- **布局与打磨三轮**：D12 七块结构、D13 结果区外框、标题栏两级标题、折叠路径、导出窗口、刷新倒计时与四档、铺满跨视图、图标按钮一律带 tooltip、冻结列阴影、列宽把手常显。
- **D16 站在 shadcn／Base UI 肩膀上**（八条裁定 + 其余替换，全部落地）：`Combobox multiple` 字段勾选表、`NumberField` 与 `Combobox` chips、表单 `Field*`、`AlertDialog`、`DropdownMenuGroup`、`Alert` 紧凑变体（[ui/README.md#三态各有一处凭据](ui/README.md#三态各有一处凭据)）、`Item` 五处行统一与 `Pagination` 外框、chrome 上的 `Tooltip`、cva 薄包装（`ui/variants.tsx`、`ui/alerts.tsx`、`ui/RowItem.tsx`）、Base UI `Toolbar`、`Collapsible` 折叠带、`InputGroup` 改名、一个 announcer。不接的两项（`@tanstack/react-table`、`@shadcn/sidebar`）理由在 [ui/record.md](ui/record.md) 与 [decisions.md#d16](decisions.md#d16-站在-shadcnbase-ui-肩膀上审计后的八条裁定)。
- **D17 十一条产品与模型裁定**（全部落地）：日期带时刻（D17-1）、软删除口径（D17-2）、一个 primary（D17-3）、冻结列封顶（D17-4）、一开就被拒的作用域（D17-5）、整份配置的「改过没应用」（D17-6，随后修正：不进查询的布局／模式不算）、仪表盘键盘编排（D17-7）、隐藏列保位（D17-8）、孤儿汇总行（D17-9）、`fve-tokens` 边界（D17-10）、删 `FieldDefinition.editor`（D17-11）。
- **引擎与结构**：D15 引擎订阅通知列表变化；R8／R9／R16 三处超线文件拆分；超预算树、软删除、日期时刻的内核规则见 [kernels.md](kernels.md)。

## 复验

按「状态不反映即缺陷」在最新 `main` 上用真浏览器逐控件走过一轮（1280 宽，Chromium）：列设置隐藏／再显示回原位；排序方向与多字段；筛选 pill 改值→带点→应用→条与行同步；软删除的缺省口径 badge 与显式选择；工具栏 `role="toolbar"` 单一 tab stop 与方向键漫游；折叠带的 `aria-expanded`／`aria-controls`；仪表盘把手方向键移动、live region 播报、摆放菜单边界禁用；管理视图改名进 `InputGroup`、删除走 `AlertDialog`；刷新四档与倒计时；铺满与折叠路径；保存与「已保存」；收起侧栏；导出窗口；查询失败的紧凑 Alert；嵌入式视图一开就被拒仍保留结果。

这轮复验修了两个缺陷：#1637（隐藏列保位）曾被 #1639 的压缩提交整份回退，以 #1653 原样重放；布局／模式切换曾亮「未应用」，以 #1654 修正。教训：压缩提交只用 `git reset --soft $(git merge-base HEAD origin/main)` 或 `rebase -i`，不要 reset 到一次 fetch 之后前进了的 `origin/main`。

## 主线：后续阶段（2026-09-21 与用户对齐）

按此顺序推进，最终达到生产级；上层目标是**项目目标**（[README.md#定位与第一性原理](README.md#定位与第一性原理)）与**用户体验**：

1. 布局与记录视图的 review
2. 分析视图——线索（2026-09-21 与用户讨论确认，到阶段再细化）：编辑区按分析师的顺序排成命名的槽「范围 → 维度 → 指标 → 怎么看」，图型按结果形态推荐、手选优先，「改了就跑」取代显式运行（待拍板，会改 D17-3），下钻（点值即加条件）；文案换成分析师语言：维度／指标／记录数／显示名／前 N 条／明细项／合计行；一列多个汇总函数（页脚按口径×函数排行，存储已是列表）；STDDEV／VARIANCE 作为分析视图的指标函数接 Wow。2026-09-22 再细一层（用户按推荐确认）：两个维度只做平铺表 + 图（堆叠/分组柱、热力图），透视表留线索；日期维度的粒度放在维度卡片上（按结果跨度自动推荐、手选优先）；前 N 组之外不合「其他」行，只说「另有 M 组未列出」；下钻在同一工作台开未保存记录视图，标题栏「来自：… · 维度=值」加返回，侧栏不加条目，日期分桶下钻为 [桶起, 下桶起) 区间（内核给分桶→条件的反向映射）；改了就跑遇慢源：300ms 合并、旧结果保留变淡、托盘「暂停自动运行」开关记个人偏好；指标数字格式沿用字段 `numberFormat`（AVG 两位小数、记录数整数）。
3. 仪表盘
4. 嵌入视图
5. 内置多主题
6. Wow 存储后端（`ViewStore` 的宿主服务，入口在 [management.md](management.md)）
7. 文档
8. view-engine skills

每个阶段：进一步打磨 **UI 视觉、UX、功能、架构质量、扩展点**。节奏是——审计清单（先给用户看，再分派）→ 条目进 [todo.md](todo.md) → 做与派 → 合并后真浏览器逐控件复验 → **阶段审查（架构、代码质量、UI、视觉、UX 五个维度）**，防止架构腐化，及时重构 → 重写本页。

**当前阶段：1. 布局与记录视图的 review**——审计已出、十二个拍板已记为 D18、清单已进 [todo.md#阶段-1-审计2026-09-21布局与记录视图的-review](todo.md#阶段-1-审计2026-09-21布局与记录视图的-review)，第一批在做：已合 A-01（#1661）、F-02／F-03（#1664、#1667——真浏览器复验时又撞到两条「状态不反映」：操作列的放掉标记与配置钉右的末列）；PR 已开或在做 F-16／17（#1663）、F-12／13（#1666）、F-18（#1665）、F-10、F-08／09／15。第一批合完后再复验一轮、派第二批。

## 上一个暂停点（已过）

1. ~~验证项目级 shadcn skill~~ **已验证**（2026-09-21，新会话）：`Skill(shadcn)` 解析到仓库的 `.claude/skills/shadcn`，preamble 带 `-c` 的 `shadcn info` 返回 base-nova / base / neutral / lucide，cwd 落在 `packages/view-engine`；全局副本已删除，`AGENTS.md` 规定不得再全局安装（同名全局副本会遮蔽仓库副本）。
2. **Wow 存储后端**（`ViewStore` 的宿主服务）是否开始，等用户决定；设计入口在 [management.md](management.md) 的 `ViewStore` 一节。
3. 搁置待议的产品问题仍在 [decisions.md#搁置待议](decisions.md#搁置待议)。
