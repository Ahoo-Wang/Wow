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

## 暂停点与下一步

1. **验证项目级 shadcn skill**：新会话已证明同名的项目级 `shadcn` 永远解析到全局副本，于是仓库副本改名为 `.claude/skills/shadcn-view-engine`（preamble 带 `-c "$(git rev-parse --show-toplevel)/packages/view-engine"`，`AGENTS.md` 的规则改为 `Skill(shadcn-view-engine)`）；再开一个新会话确认它被解析到。
2. **Wow 存储后端**（`ViewStore` 的宿主服务）是否开始，等用户决定；设计入口在 [management.md](management.md) 的 `ViewStore` 一节。
3. 搁置待议的产品问题仍在 [decisions.md#搁置待议](decisions.md#搁置待议)。
