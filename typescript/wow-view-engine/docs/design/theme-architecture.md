# 方案：主题架构重构（首发前）

**状态**：已拍板（2026-09-25，用户：「基于第一性原理，按你推荐。」），裁定见 [D46](decisions.md#d46-主题架构重构五条结构一张登记表2026-09-25)。批次从 S1 起按序开工，每批合并后在 [todo.md](todo.md) 与 [progress.md](progress.md) 更新暂停点；全部落地后本页并入 [themes.md](themes.md) 与 [ui/README.md#主题弹层与明暗](ui/README.md#主题弹层与明暗)。
**进度**：S1（登记表）已完成，PR #S1PR。登记表是 `src/ui/theme/` 的三个文件：`tokens.ts`（结构，生成 `FveToken`、`CHART_TOKENS`、`THEME_ATTRIBUTES` 与构建写出的 `dist/theme-tokens.json`）、`tokenDocs.ts`（README 两张表的中英措辞，与结构分开，运行时不带）、`pairs.ts`（底的列表与每一对、线；jsdom 与 Storybook 矩阵都从它展开）；`resolveTokens` 快照在 `test/snapshots/resolvedTokens.json`。下一批 S2。
**日期**：2026-09-25（内置主题 T1～T5 已合并、T5 截图基线已在 CI 之后）
**来由**：用户 2026-09-25 同意协调者的第一性原理审查方向——主题系统在首个 npm 版本之前重构一次结构（本包在 `HELD_BACK`，没有兼容负担）；同日并行的视觉保真走查（第 8 节）给出「八套预设都只是换色」的结论与所需的扩展点。
**读法**：第 0 节是结论；第 1 节讲为什么；第 2～6 节是五个结构问题，每节都按「现状（带文件与行号）→ 目标（带示意）→ 理由 → 代价与风险 → neutral 像素怎么证 → 门怎么变」写；第 7 节是折进批次的局部项；第 8 节是视觉走查结论；第 9 节是批次；第 10 节是已定的问题；第 11 节是考虑过、没选的方案。

**固定前提（用户的常设决定，本页不重议）**：

- CSS 变量是运行时唯一的真相源；JS 里没有主题上下文 API（React context 只搬运级联送不到的东西）。
- 主题由宿主研发选，引擎里没有终端用户的选择器。
- 预设只用公开合同；合同表达不了的是**机制的缺口**，在机制里修，绝不给某一套开特例。
- 可达性线对内置预设有保证：字 ≥4.5:1，控件边与焦点 ≥3:1，图表色觉间距过门。
- **每一批都让 `neutral` 逐像素不变**，由 T5 的截图基线（[D45](decisions.md#d45-主题一览拆成每套一个故事截图基线在-playwright-的-linux-容器里截2026-09-25)，比对不留容差）证明。
- [themes.md](themes.md) 1.2 的排除项（网络字体、组件形状变体、毛玻璃、动效、布局尺寸）不变，除非在本页论证（第 4.6 节论证了其中三处的边界）。
- 体积不是目标，功能与体验优先。

## 0 结论

| #   | 问题                                                                                                | 定下的做法                                                                                                                                                                | 批次   |
| --- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| 1   | **轴**：品牌色是一套预设（`brand`），与风格互斥                                                     | `--fve-brand` 成为**每一套**预设都接受的输入；派生式只写一处（`styles.css`），各预设只给夹子的边界；不给品牌色时派生无效、落回字面量；删掉 `brand` 预设                   | S4     |
| 2   | **层**：宿主与预设写同一组 `--fve-*`，钉在面上的预设压过宿主的 `:root`，与 README 的承诺相反        | 三层：预设写 `--fvp-*`，宿主写 `--fve-*`，引擎读 `var(--fve-x, var(--fvp-x, 内置值))`；一条零权重的复位规则让钉住的预设完整替换外层预设；桥接写预设层；私有变量换私有前缀 | S2     |
| 3   | **角色**：语义层只有 shadcn 那一组通用名，`muted`、`background` 身兼数职；可选组零敲碎打长到九个    | 一层固定的**角色**（引擎自己的面：底、表头、选中行、合计带、焦点、控件、形状、菜单高亮、提示框、图表……），每个角色落回一个语义 token，内置值等于今天画出来的样子          | S3     |
| 4   | **合同的真相源**散在 README 表、`verify-package`、两份对比度对表、`CHART_TOKENS` 与文档里，已经漂移 | 一张机器可读的**登记表**（TS 模块）生成 README 中英两张表、**FveToken** 类型、`verify-package` 的规则、两份对表与图表 token 表；值仍只在 CSS 里；加开发期的 theme-check   | S1、S7 |
| 5   | **图表只拿到颜色**：网格色、字号 11／12、柱圆角 2px、提示框的底与阴影写死                           | 图表主题整体从角色 token 读出（颜色、长度、数字都经探针由浏览器算），`ChartTheme` 扩成完整的图表外观                                                                      | S5     |

**不变的**：`--fve-*` 仍是宿主唯一的入口；预设名（除 `brand`）、`data-fve-*` 四个属性、`theme`／`preset`／`density` prop、单套文件与 `themes.css`、桥接文件都在；组件形状变体、网络字体、毛玻璃、动效与布局尺寸仍不进主题。

**批次一览**（第 9 节）：S1 登记表 → S2 三层 → S3 角色 → S4 品牌是输入 → S5 图表读角色 → S6 宿主文档与样板 → S7 theme-check；S3 之后每套预设各一批重调（S8 azure、S9 porcelain、S10 graphite、S11 contrast、S12 fjord、S13 slate），各有目标分与截图。S1～S7 每批 neutral 逐像素不变；重调批只改它自己那一套的基线。

## 1 为什么现在、为什么这样改

主题目录的两个目的（[themes.md](themes.md) 1.1，T2 起每批按它验收）：

1. **研发能快速穿上自己品牌的外观。**
2. **证明主题系统可扩展、可定制**：内置预设只经宿主也能用的公开合同搭出来。

T1～T5 做成了「八套、每套亮暗、量过线」，但用这两个目的回头看，结构上有五处站不住：

- 一个研发拿着「我们用 Ant 风格、品牌色是紫色」来接入，今天做不到：`azure` 与 `brand` 只能二选一（问题 1）。
- 他在 `:root` 上改了一个主色，页面上钉了预设的那块嵌入不听他的（问题 2）。
- 他想让选中行是品牌淡色、表头不要灰底——合同里没有「选中行」「表头」这些词，只有 `muted`，改它就连合计带、按下态一起改了（问题 3）。
- 他想知道合同有哪些变量、每个欠哪条线，README 说「四个可选组」，实际九个（问题 4）。
- 他的风格是方角，图表里的柱子还是圆角（问题 5）。

第 8 节的视觉走查从另一头得出同一个结论：**八套预设只是换了颜色**。选中色、控件高度、表头样式、焦点、菜单高亮、图表样式在每一套里都一样，而让人一眼认出「这是 Ant／macOS／运维看板」的恰恰是这些。它们不是值没调好，是合同里没有表达它们的词——按「缺口修在机制里」，这就是本页。

一套主题有五条互相正交的轴：

| 轴                           | 载体                                                                       | 谁定               | 今天                     | 目标                                             |
| ---------------------------- | -------------------------------------------------------------------------- | ------------------ | ------------------------ | ------------------------------------------------ |
| 风格（形状、面的层次、字体） | `data-fve-preset`／`preset` prop                                           | 宿主               | 预设，但只能表达颜色     | 预设，经角色层表达形状与层次（问题 3）           |
| 品牌色                       | `--fve-brand`（暗色 `--fve-dark-brand`）                                   | 宿主               | 一套叫 `brand` 的预设    | 任何预设上的一个输入（问题 1）                   |
| 明暗                         | `.dark`、`theme` prop                                                      | 宿主（可跟随系统） | 已正交                   | 不变                                             |
| 密度                         | `data-fve-density`／`density` prop，预设只写推荐（`--fve-preset-density`） | 宿主               | 已正交（两个变量，见下） | 不变；推荐改写到预设层（`--fvp-preset-density`） |
| 涨跌约定                     | `data-fve-change-colors`                                                   | 宿主               | 已正交                   | 不变                                             |

密度是今天唯一把「预设的推荐」与「宿主的选择」拆成两个变量的轴（[themes.md](themes.md):103）：两者写不同的变量，所以宿主的选择在任何嵌套下都赢。问题 2 的解法就是把这条已经证明可行的做法推广到每一个 token。

## 2 问题一：品牌色是输入，不是预设

### 2.1 现状

- `brand` 是第八套预设：`neutral` 的一切加上从 `--fve-brand` 派生的主色、`accent`、`sidebar-accent`（`src/themes/brand.css:51-117`）。要一个品牌色就得放弃风格；想要「azure 的形状、自己的紫色」只能自己抄 azure 再改主色。
- 派生式写在预设块里，所以 `var(--fve-brand)` 在**预设所在的元素上**替换：品牌色必须挂在同一个元素或更外层（`brand.css:20-23` 的注释，[themes.md](themes.md) 2.7「挂在哪」）。宿主把品牌色写在某个包裹层上、预设在 `<html>` 上，派生就无效。
- 品牌色只落在很小的面积上（主色、极淡的 `accent`）；图表第 1 色仍是默认蓝，与品牌主色撞在一起（第 8 节）。
- 扫描单测只量 `brand` 这一套（`test/brandPreset.test.ts`，1 314 个颜色 × 两种明暗 × 两种回到色域的方式）。

### 2.2 目标

派生式只写一处，在 `styles.css` 的边界上；各预设只给它的**夹子边界**（预设层的数字），因为同一个品牌色放在 azure 的灰底和 porcelain 的白底上，能过线的亮度区间不同。

```css
/* styles.css：派生在边界上算，--fve-brand 挂在任何祖先上都行 */
@supports (color: oklch(from red l c h)) {
  :where(.fve-root, .fve-tokens) {
    --_fve-brand-primary: oklch(
      from var(--fve-brand)
        clamp(var(--fvp-brand-l-min, 0.4), l, var(--fvp-brand-l-max, 0.5))
        min(c, var(--fvp-brand-c-max, 0.37)) h
    );
    --_fve-brand-tint: oklch(
      from var(--fve-brand) var(--fvp-brand-tint-l, 0.96)
        var(--fvp-brand-tint-c, 0.02) h
    );
    --_fve-brand-selected: oklch(
      from var(--fve-brand) var(--fvp-brand-selected-l, 0.95)
        var(--fvp-brand-selected-c, 0.03) h
    );
    /* 预设不给焦点的边界，这条就无效、焦点不跟品牌色（第 2.3 节） */
    --_fve-brand-ring: oklch(
      from var(--fve-brand)
        clamp(var(--fvp-brand-ring-l-min), l, var(--fvp-brand-ring-l-max)) c h
    );
  }
}

:where(.fve-root, .fve-tokens) {
  /* 宿主明写的主色 > 品牌派生 > 预设的字面量 > 内置值 */
  --primary: var(
    --fve-primary,
    var(--_fve-brand-primary, var(--fvp-primary, oklch(0.205 0 0deg)))
  );
  --accent: var(
    --fve-accent,
    var(--_fve-brand-tint, var(--fvp-accent, oklch(0.97 0 0deg)))
  );
  --_fve-row-selected: var(
    --fve-row-selected,
    var(--_fve-brand-selected, var(--fvp-row-selected, var(--muted)))
  );
}
```

- **派生的对象**：`primary`（含暗色一半）、`accent`、`sidebar-accent`、选中行（角色 `row-selected`，第 4 节）；焦点 `ring` 只在预设给了焦点边界时派生（porcelain、graphite 这类焦点本来就跟主色的）。暗色一半读 `var(--fve-dark-brand, var(--fve-brand))`，边界读 `--fvp-brand-dark-*`。
- **图表第 1 色跟品牌色是宿主的开关**（第 10 节已定）：`--fve-brand-chart: 1` 打开，取品牌的色相、保留预设第 1 色调好的亮度与彩度（这样柱内墨色与对底对比不变）；不写就关。这个开关不在任何预设里，因为品牌色相与第 2、8 色的色觉间距对任意品牌色证明不了——开了就像宿主覆盖 `--fve-chart-*` 一样，欠色板门的量（theme-check 能量，第 5 节）。
- **没给品牌色**：`--_fve-brand-*` 在计算期无效（引用了没有后备的未设变量），`var()` 落到下一层——就是今天的字面量，像素不变。
- **老浏览器**：不支持相对颜色时 `@supports` 整块不生效，同样落到字面量；不会出现「颜色失效成透明」。
- **删掉 `brand` 预设**（已定，不留别名）：原来的 `data-fve-preset="brand"` 就是「不挂预设（或 `neutral`）加 `--fve-brand`」。

### 2.3 理由

- **轴要正交**：风格与品牌色是宿主两个独立的决定，一个值只能二选一，就说明它们被放错了层。
- **派生放在边界上**：`var()` 在声明它的元素上替换（[themes.md](themes.md) 2.6 的坑）。算在读它的地方，品牌色挂在任何祖先上都对，宿主不必知道这条规则。
- **夹子边界跟预设走**：线是在预设自己的底上量的（带底、分组底、控件填色各不同），所以边界是预设的事；边界的缺省值就是今天 `brand` 的 0.40～0.50，已被扫描证明。
- **缺参数就不派生**：焦点跟不跟品牌色用「预设给没给边界」表达，不需要开关、不需要特例——CSS 的无效值语义自己就回答了。

### 2.4 代价与风险

- 每套预设要为品牌色调一组边界，并在扫描里全过；某套在某个色相上过不了，就收窄它的边界（品牌色被压得更深或更浅，这是可达性优先的有意取舍，文档写明）。
- 扫描从 1 套扩到 7 套：约 1 314 × 7 × 2 × 2 ≈ 3.7 万次解析，`themeTokens.ts` 已有缓存，T3 实测一套几百毫秒，七套在单测预算内；如超时，按色相分片成 `it.each`，不放宽时限。
- Storybook 的 `.storybook/preview.css:12` 像宿主一样全局设了 `--fve-brand: #7c3aed`。品牌色成为输入后它会给**每一套**（包括 neutral）上色，截图全变。S4 把它挪进只给「品牌色」故事用的装饰器；这一步是 S4 的第一件事。

### 2.5 neutral 像素怎么证

截图基线里没有品牌色（S4 先挪走 Storybook 的全局品牌色），所以 `--_fve-brand-*` 全部无效，每个 token 落回 S3 之后的值；截图 51 张逐像素相同。jsdom 侧：「没有品牌色」对每一套都解析出与 S3 快照相同的 token（把 `test/brandPreset.test.ts` 里「no brand colour is neutral」推广成「no brand colour is the preset」）。

### 2.6 门怎么变

- `test/brandPreset.test.ts` 的扫描对**每一套**预设跑，夹具里的每一对都要过该预设的线（`PRESET_LINES`，contrast 是 7／4.5）。
- 新增：品牌色挂在包裹层、预设挂在 `<html>` 时派生仍生效（今天这一种会失效）。
- `verify-package`：`themes.css` 不再有任何 at-rule（`brand` 的 `@supports` 挪进了 `styles.css`）。

## 3 问题二：预设层与宿主层分开

### 3.1 现状

- 宿主与预设写**同一组**变量 `--fve-*`。预设块是 `:where([data-fve-preset='…'])`，零权重，所以写在 `:root` 上的宿主值能赢过**挂在 `<html>` 上的**预设——但那靠的是「两者在同一个元素上、宿主的权重高」。
- 预设一旦钉在面上（`preset` prop，面在自己的根上写 `data-fve-preset`），预设的值就**声明在面的根上**，宿主的值只是从 `:root` 继承下来的。声明在元素上的值永远赢过继承来的值，权重与 `@layer` 都改变不了这一点。结果：钉住的预设压过宿主的 `:root`，与 README:507（「Your own variables win … whichever stylesheet loads first」）和主题指南英文版 :98 的承诺相反。
- 为了「钉成某套就完整替换外层那套」，每套内置预设都要把它不改的变量写成 `initial`：`neutral` 96 行全是 `initial`，`brand` 50 行，其余每套几十行（`src/themes/neutral.css`，`brand.css:53-116`）。宿主自己的预设不写这些，于是嵌套时会继承外层预设的值（`host-theme/acme.css:35-38` 的注释承认了这一点）。
- 桥接与预设写同一组变量，所以需要「桥接只在没有预设时生效」这条规则（[themes.md](themes.md) 2.8），并在 `verify-package` 里单独守。
- 私有变量也用公开前缀：`--fve-expanded-x/y/w/h`（`src/ui/ViewExpansion.tsx:139-142`，`styles.css:1649-1654`）、`--fve-pin-left-{i}`（`src/ui/record/sticky.ts:294`）、`--fve-tap-hint`（`src/ui/charts/EChart.tsx:542`）。宿主看到 `--fve-` 会以为能写。
- 反过来，`.fve-tokens`（宿主自己的 chrome 上的边界）声明了一批通用名：`--canvas`、`--control`、`--density`、`--change`、`--rise`、`--fall`、`--card-edge`、`--title-weight`、`--table-head-height`、`--panel-padding`……（`styles.css:494-503`、`530-575`、`599-625`）。宿主自己如果有同名变量，在 `.fve-tokens` 里会被悄悄遮住。shadcn 的语义名（`--background`、`--primary`……）遮住是**有意的**（边界的用途就是让宿主 chrome 用上我们的 token，D17-10），这些引擎自己的名字不是。

### 3.2 目标：三个前缀、三层

| 前缀          | 谁写                                     | 例子                                       | 读的地方                                                     |
| ------------- | ---------------------------------------- | ------------------------------------------ | ------------------------------------------------------------ |
| `--fve-*`     | 宿主（`:root`、任何祖先、`tokens` prop） | `--fve-primary`、`--fve-brand`             | 引擎在边界上读，**永远最先**                                 |
| `--fvp-*`     | 预设（内置的与宿主写的）、桥接           | `--fvp-primary`、`--fvp-brand-l-max`       | 引擎在边界上读，宿主没写时才用                               |
| `--_fve-*`    | 引擎自己（算出来的角色、中间量、测量值） | `--_fve-row-selected`、`--_fve-pin-left-0` | 引擎内部；不是合同，登记表里没有它们                         |
| shadcn 语义名 | 引擎（`styles.css` 的 token 块）         | `--primary`、`--muted`                     | vendored 组件与工具类；`.fve-tokens` 有意把它们给宿主 chrome |

```css
/* styles.css：token 块里每一行都是同一个形状，由登记表检查 */
:where(.fve-root, .fve-tokens) {
  --muted: var(--fve-muted, var(--fvp-muted, oklch(0.97 0 0deg)));
}

/* styles.css：钉住的预设完整替换外层预设——一条零权重规则，放在最低的层里 */
@layer fve-reset {
  :where([data-fve-preset]) {
    --fvp-background: initial;
    --fvp-muted: initial;
    /* ……登记表里预设层的每一个名字，由构建生成 */
  }
}

/* src/themes/azure.css：只写它改的；权重一个属性，赢过复位 */
[data-fve-preset='azure'] {
  --fvp-canvas: oklch(0.9702 0 0deg);
  --fvp-primary: oklch(0.541 0.1928 258.885deg);
}

/* shadcn-bridge.css：写预设层，所以宿主的 --fve-* 仍然赢 */
:where(:root:not([data-fve-preset])) {
  --fvp-primary: var(--primary);
}
```

- **宿主永远赢**：宿主与预设写不同的变量，谁声明在哪个元素上不再决定输赢，`var()` 的顺序决定。README 与指南的承诺从「碰巧成立」变成「结构上成立」。宿主若要让某块钉住的面**不**受它的覆盖影响，就把覆盖写在更窄的选择器上（例如 `:root:not(…)` 或只写在页面主区的包裹层上）——这是宿主在两个自己的决定之间取舍，文档给写法。
- **钉住即完整替换，不再需要 `initial`**：复位规则匹配每个挂了 `data-fve-preset` 的元素，把预设层清空；预设块的权重（一个属性）高于复位（零），所以只有它写了的那些留下。内置预设的源文件从此只写它改的值（`neutral` 变成空块；构建产物里 `neutral` 只有选择器本身也可以，名单仍由 `BUILT_IN_PRESETS` 给），宿主的预设同样不必写，嵌套也对。
- **复位放在最低的层**：一个宿主若把自己的预设写进了自己的 `@layer`，未分层的复位会压过它；放进 `@layer fve-reset` 后，只有「宿主的层在我们之前声明」且「宿主的预设在层里」两件事同时成立时才会输。README 写明「预设写在层外」，theme-check 检查宿主 CSS 里包着预设块的 `@layer`（第 5 节）。
- **桥接写预设层**：桥接因此与预设同层、同规则，钉住的预设复位后自然替换桥接给的值，不再需要「二选一」的特例规则（`:not([data-fve-preset])` 仍保留，理由是 `<html>` 上既有桥接又有预设时要预设——与今天相同）。
- **私有前缀**：`--fve-expanded-*`、`--fve-pin-left-*`、`--fve-tap-hint` 改成 `--_fve-*`；`.fve-tokens` 上引擎自己的名字（`canvas`、`control*`、`density`、`change`、`rise`、`fall`、`convention-*`、`card-*`、`title-weight`、四个密度长度、`row-hover`、`quiet-foreground`、`pin-shadow`、`text-ui`）改成 `--_fve-*`。工具类的名字不变（`@theme inline` 里 `--color-canvas: var(--_fve-canvas)`，`bg-canvas` 照旧给宿主用）。
- **两个没写进文档的宿主变量**：`--fve-workbench-min-height`（`src/ui/WorkbenchShell.tsx:604`）与 `--fve-record-text-max-w`（`src/ui/record/cells.tsx:53`）是真正给宿主用的长度，进登记表（第 5 节）与 README；它们不属于主题，登记表里标「布局」一类。

### 3.3 理由

- **层是「谁说的」，不是「写在哪」**：CSS 里「写在哪个元素上」决定继承与覆盖，但宿主与预设的优先级是一个产品规则（宿主永远赢），不该依赖元素位置。用不同的变量名把层表达出来，优先级就只剩 `var()` 的后备顺序这一处，一眼看得出。
- **密度已经证明了这条路**（[themes.md](themes.md):103）；本页只是把它推广到每个 token。
- **公开前缀只给合同**：`--fve-` 下的每个名字都应当是宿主能写、登记表里有、README 里有的；其余的都不该让宿主看见。

### 3.4 代价与风险

- 一次机械的大改名：八个预设源文件、桥接、`styles.css` 的 token 块、测试夹具 `themeTokens.ts`（它按预设、明暗、约定解析 token，要学会三层与复位）、Storybook 的 `acme.css`。改名本身不改任何值。
- `verify-package` 的「构建出的样式表不画 `.fve-root` 之外的任何东西」要为复位规则开一条明确的例外：它只把 `--fvp-*` 设成 `initial`，不画任何东西，断言它的声明集合等于登记表的预设层。
- 宿主若曾经依赖「钉住的预设压过自己的 `:root`」——据我们所知没有宿主（本包未发布），首发前改。

### 3.5 neutral 像素怎么证

S2 是纯机制：对**每一套**预设、每种明暗、每种涨跌约定，`resolveTokens` 解析出的 token 与 S1 结束时存下的快照完全相同（新的快照单测，S1 先把快照存下来）；截图 51 张全部逐像素相同（不只 neutral）。唯一有意的行为变化是「钉住的预设不再压过宿主的 `:root`」，由新的单测与浏览器故事守，截图里没有这种场景。

### 3.6 门怎么变

- `verify-package`：预设块只许赋 `--fvp-*`；复位规则的集合等于登记表的预设层；「必选集合相同」这条删掉（预设只写它改的）；可选组「全给或全不给」改成登记表里标了 `whole` 的组（图表八色 16 个、阴影 6 个）才要求；每个有暗色一半的 token 两半一起给。
- `test/themeFiles.test.ts` 改读登记表（README 由登记表生成，第 5 节）。
- 新单测：宿主 `:root` 上的 `--fve-primary` 在钉了任意预设的面上都赢；钉住的预设在外层是另一套时完整替换；宿主写的、不带 `initial` 的预设嵌套时不继承外层的值。

## 4 问题三：角色层

### 4.1 现状

语义层是 shadcn 那一组通用名，它描述的是「颜色的用途类别」，不是「引擎的哪一块面」，于是一个名字身兼数职：

- **`muted` 同时是**表头带与合计带（`BAND = 'bg-muted'`，`src/ui/record/sticky.ts:260-261`）、选中行（`data-[state=selected]:bg-muted`，`src/ui/variants.tsx:644`）、分段控件的按下态、侧栏的底。表头的字重是 vendored 表格写死的 `font-medium`（`src/ui/components/table.tsx:70`）。一套预设想要「表头无底、选中行是品牌淡色」，只能改 `muted`，合计带与按下态一起跟着变。
- **`background` 同时是**页面的底与内容的底（表格行 `bg-background`，`variants.tsx:642`）。azure、graphite、fjord 把 `background` 设成灰（`src/themes/azure.css:57`、`graphite.css:60`、`fjord.css:57`），于是它们的**表格行也是灰的**；porcelain 用了 D43 加的 `canvas` 才把两者分开。
- **焦点**是 vendored 的 1px `border-ring` 加 3px `ring-ring/50` 光晕（`src/ui/components/button.tsx:6`、`input.tsx:11`）。WCAG 2.4.13（AAA）要求焦点指示 ≥2px 的周长；`contrast` 承诺 AAA，焦点却是 1px 边加 50% 光晕；graphite 的 2px 内描边写在规格里但没做（[themes.md](themes.md):489、511）。
- **可选组零敲碎打地长**：图表八色、阴影、字体、花纹、推荐密度、`canvas`、卡片、控件、标题字重，已经九个（`scripts/verify-package.mjs:351-365`），每个都是「某套预设发现合同说不出来」时加的。它们其实都是引擎自己的面，只是没有一个统一的层来放。

### 4.2 目标：一层固定的角色

**角色**是引擎自己画的某一块面的某一个属性。每个角色：

- 宿主写 `--fve-<角色>`、预设写 `--fvp-<角色>`，引擎在边界上解析成 `--_fve-<角色>`；
- **不设时落回一个语义 token 或今天的值**，所以内置值就是今天画出来的样子；
- 由 `styles.css` 里对 `data-slot` 的规则施加（`--text-ui` 与密度已经这样做了，`styles.css:661-685`、`729-734`），**vendored 的 shadcn 组件一个字不改**；我们自己的配方（`variants.tsx`、`sticky.ts`）直接读角色。

没有内置值的角色（今天各处的值本来就不同，例如焦点、控件填色）用「不设就整条声明无效、回到组件原来的样子」表达，与 D43 的 `control` 同一种做法（`styles.css:829-855`）。

| 组         | 角色                                                                                        | 不设时                                                                                                                      | 今天画在哪                                                               | 来由                                         |
| ---------- | ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | -------------------------------------------- |
| 底与面     | `canvas`                                                                                    | `background`                                                                                                                | 看板的根（`styles.css:1036`）                                            | D43 的 `canvas` 组并入                       |
|            | `content`                                                                                   | `background`                                                                                                                | 记录表与分析表的行、结果块（`variants.tsx:642`）                         | 本页，解 azure／graphite／fjord 的灰行       |
|            | `card-edge`、`card-shadow`                                                                  | 前景 10%、无阴影                                                                                                            | `CARD_LIFT`                                                              | D43 的卡片组并入                             |
|            | `scrim`                                                                                     | 黑 10%                                                                                                                      | 对话框与抽屉的遮罩（`components/dialog.tsx:34`、`sheet.tsx:29`）         | 本页                                         |
| 表格       | `table-header`、`table-header-foreground`、`table-header-weight`、`table-header-divider`    | `muted`、`foreground`、500、无                                                                                              | `BAND`、`table.tsx:70`                                                   | 走查：Ant 的 #fafafa、600、列分隔            |
|            | `totals`                                                                                    | `muted`                                                                                                                     | `BAND_ROW`                                                               | 与表头拆开                                   |
|            | `row-selected`、`row-selected-foreground`                                                   | `muted`、`foreground`                                                                                                       | `variants.tsx:644`                                                       | 走查 P0                                      |
|            | `row-hover`                                                                                 | `muted` 50% 混进底（今天的派生）                                                                                            | 已有                                                                     | 已有 token 改为角色                          |
|            | `row-stripe`                                                                                | 无（默认关）                                                                                                                | 今天没有                                                                 | 走查：macOS 的交替行；第 10 节已定           |
| 选中与状态 | `highlight`、`highlight-foreground`                                                         | `accent`、`accent-foreground`                                                                                               | 菜单、选择框、组合框的高亮项（`dropdown-menu.tsx:90`、`select.tsx:119`） | 走查：填主色白字 vs 淡色                     |
|            | `nav-current`、`nav-current-foreground`                                                     | 今天的值（侧栏里开着的视图是 `sidebar` 上的一块 `background`，旁边一条 2px `primary`）                                      | 侧栏视图列表                                                             | 走查：Ant 的蓝底蓝字                         |
|            | `control-hover`、`control-pressed`                                                          | 今天 registry 的悬停与按下填色                                                                                              | 按钮、切换、分段控件                                                     | 走查：悬停只差 1.5% 亮度                     |
| 焦点       | `focus-width`、`focus-offset`、`focus-style`、`focus-halo`                                  | 无内置值：不设就是 vendored 的 1px 边加 3px 50% 光晕                                                                        | `button.tsx:6`、`input.tsx:11`                                           | 走查 P0；WCAG 2.4.13                         |
| 控件       | `control`、`control-edge`、`control-thumb`、`control-thumb-shadow`                          | 无内置值（各控件原来的值）；滑块阴影无                                                                                      | 分段控件、看板筛选条（`ControlFrame`）                                   | D43 的控件组并入；走查：滑块读反了           |
|            | `control-height`、`control-height-sm`                                                       | 32px（`h-8`）、28px（`h-7`）；筛选条今天是 34px，按 `control-height` 加两条 1px 边解释，S3 实测确认（不成立就单列一个角色） | 对话框输入框、工具栏按钮、筛选条                                         | 走查：一套控件高度梯级                       |
|            | `edge-width`                                                                                | 1px                                                                                                                         | 输入框、复选框、描边控件的边                                             | 走查：contrast 的 1px 边                     |
|            | `badge-edge`、`badge-fill`                                                                  | 今天 `ToneBadge` 的 30% 同色边与 10% 同色底                                                                                 | `ToneBadge`                                                              | 第 10 节：边默认保留                         |
| 形状       | `radius-card`、`radius-control`、`radius-popover`、`radius-badge`、`radius-checkbox`        | 今天各自的派生值（卡片 `rounded-xl` 即 1.4 倍 `--radius`，控件 `rounded-lg`，复选框写死的 4px）                             | vendored 组件的 `rounded-*`                                              | 走查：macOS 控件 5～6px、方角里的 4px 复选框 |
| 字重       | `title-weight`、`strong-weight`                                                             | 500、500                                                                                                                    | 视图、卡片、**对话框**标题；表头、合计标签、提示框标题                   | D43 的标题组并入；对话框标题是缺陷 2         |
| 浮层       | `tooltip`、`tooltip-foreground`                                                             | `foreground`、`background`（vendored 提示框本来就是反色）                                                                   | `components/tooltip.tsx`                                                 | 本页                                         |
| 图表       | 见第 6 节（网格、坐标轴、字号、线宽、面积不透明度、柱宽上下限、柱圆角、扇区边、图表提示框） | 今天的值                                                                                                                    | `src/ui/charts/*Option.ts`                                               | 问题五                                       |

**哪些可选组并进角色**：`canvas`、`card`、`controls`、`title` 四组并进角色（它们本来就是引擎自己的面，是按角色逐个加进来的）；图表八色、阴影三档、字体栈、图表花纹、推荐密度留作**预设的参数组**（它们不是某一块面，是一套调色板、一套层级、一种字体、一个开关、一个档位），在登记表里标为 `group`，其中八色与阴影要求「全给或全不给」。

```css
/* styles.css：角色的解析——一行一个，形状由登记表检查 */
:where(.fve-root, .fve-tokens) {
  --_fve-table-header: var(
    --fve-table-header,
    var(--fvp-table-header, var(--muted))
  );
  --_fve-row-stripe: var(--fve-row-stripe, var(--fvp-row-stripe, transparent));
  --_fve-focus-width: var(
    --fve-focus-width,
    var(--fvp-focus-width)
  ); /* 没有内置值 */
}

/* 施加：对 data-slot 的规则，放在 utilities 层，重一个属性（与 D43 的控件规则同一种权重安排） */
@layer utilities {
  :where(.fve-root, .fve-tokens)
    [data-slot='table-row'][data-state='selected'] {
    background-color: var(--_fve-row-selected);
  }
  :where(.fve-root, .fve-tokens) [data-slot='table-head'] {
    font-weight: var(--_fve-table-header-weight, 500);
  }
  /* 不设 focus-width：整条 outline 在计算期无效，回到 vendored 的 outline-none，与今天相同 */
  :where(.fve-root, .fve-tokens) :focus-visible {
    outline: var(--_fve-focus-width) var(--_fve-focus-style, solid) var(--ring);
    outline-offset: var(--_fve-focus-offset, 0px);
  }
}
```

### 4.3 理由

- **合同的词要对准引擎的面**：研发想改的是「选中行」「表头」，不是「`muted` 用在哪些地方」。角色把「一块面」与「一个颜色」解开：面各自可调，不设时仍共享今天的那个语义色。
- **一个固定的层，而不是按预设发现的缺口一个个加组**：九个可选组是同一个问题被发现了九次。一次把引擎自己的面列全（本页加第 8 节的走查清单），以后再有预设说不出来的，就是这张表漏了一行——加一行，不是加一个组。
- **落回语义 token**：保证「什么都不设的宿主」与今天一模一样，也保证只改了 `muted` 的宿主，表头、合计带、选中行仍跟着它（这正是 shadcn 模型给宿主的便利）。
- **vendored 组件不动**：`--text-ui` 与密度已经证明对 `data-slot` 的规则能在不改 registry 文件的前提下施加（[themes.md](themes.md) T4 落地记录）；D16 的「不按类名选」仍守着——选择器用 `data-slot`、`data-state`、`data-variant`，不用类名。

### 4.4 代价与风险

- 角色约 50 个（含明暗两半的颜色角色），登记表、README 表、`verify-package` 都由登记表生成（第 5 节），所以数目本身不增加手写的维护面；增加的是 `styles.css` 的解析行与 `data-slot` 规则。
- **焦点规则最敏感**：`:focus-visible` 的轮廓与 vendored 的 `outline-none`、光晕的 `box-shadow` 叠在一起，`forced-colors` 下的 T5 规则（`styles.css:1139-1160`）也要一起过。S3 在三个引擎里用浏览器故事量「设了 2px 时轮廓是实线 2px、偏移正确、光晕按 `focus-halo` 去留；不设时与今天逐像素相同」。
- **控件高度与 1.2 的「布局尺寸」**：见 4.6 的论证；地板 24px（WCAG 2.5.8）由单测守。
- 一个角色施加的面若没有 `data-slot`（例如某处用类名画的带），要先让组件在元素上说出来（A-09 的规矩），这些改动算在 S3。

### 4.5 neutral 像素怎么证

S3 只加机制、不改任何预设：每个角色不设时的值就是今天的值，所以**八套**的截图 51 张都逐像素相同；jsdom 侧，`resolveTokens` 对每套的快照不变（新增的角色解析成它的后备 token）。表头、合计带、选中行、焦点这些今天的截图里有的面，各有一个浏览器故事量计算样式（沿用 T4「量每个 `data-slot` 元素的位置与尺寸」的做法）。截图里没覆盖的面（菜单高亮、遮罩、提示框）加**新的**截图故事，不改已有的截图故事——改了已有故事的内容，就失去了「逐像素相同」这个证据。

### 4.6 1.2 的边界：哪些进来、为什么

[themes.md](themes.md) 1.2 排除「组件形状变体」与「布局尺寸」，D43 已经把「卡片怎样浮起」「控件填色还是描边」「标题字重」划为主题。走查要的扩展点里有三类需要论证：

- **分部件的圆角**（`radius-card`／`-control`／`-popover`／`-badge`／`-checkbox`）：圆角本来就在主题里（`--fve-radius`），只是只有一个旋钮、按固定倍数乘出来。macOS 的控件圆角约 5～6px、卡片约 10px，一个倍数表达不了；方角风格里 4px 的复选框刺眼。这是同一个量分部件给值，不是形状变体。**进。**
- **控件高度梯级**（`control-height`、`-sm`）：1.2 排除的布局尺寸是「存下的几何」（仪表盘 80px 行高、1200px 定宽，D34、D31），换主题会让存下的板子变形。控件高度不进存下的几何，Ant 的 32px 与我们的 28px 是风格的一部分。与密度的区别：密度动的是表格与面板的内边距、行高，预设只推荐；控件高度是风格，预设给值；两者都守 24px 的点击目标地板。**进。**
- **线宽**（`edge-width`，只到控件边）：1.2 说线宽不进主题，理由是分隔线。控件边是可达性的一部分（低视力读者要更粗的边），`contrast` 需要它兑现承诺。只开控件边，分隔线仍是 1px。**进，范围限于控件边。**
- **菜单高亮方式、徽标样式**：是「用哪对颜色」而不是组件结构——填主色白字就是 `highlight: var(--primary)`、`highlight-foreground: var(--primary-foreground)`，淡色就是今天的 `accent`。不需要「模式」开关，也不是形状变体。**作为颜色角色进。**
- **仍然不进**：药丸按钮、浮动标签这类改结构的变体；网络字体；毛玻璃；动效；存下的几何。

### 4.7 门怎么变

- `test/fixtures/presetPairs.ts` 与 Storybook `themeContrast.tsx` 的对表**都由登记表生成**（第 5 节），角色带来的新底（表头、选中行、合计带、斑马纹、内容底、菜单高亮、提示框）自动进矩阵。
- 徽标在选中行上 ≥1.5:1 这条线（P-21）改成「在 `row-selected` 上」量；预设若把 `badge-edge` 设成透明，这一对必须仍过 1.5:1（第 10 节）。
- 新单测：控件高度的每一档 ≥24px；`focus-width` 设了时 ≥2px（AAA 预设）；角色不设时的解析值等于它的后备。

## 5 问题四：合同只有一个来源——登记表

### 5.1 现状

同一份合同今天写在六处，已经漂移：

| 在哪                                                                                        | 写的是                                 | 漂移的证据                                                                                                              |
| ------------------------------------------------------------------------------------------- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| README 与 README.zh-CN 的 token 表（`README.md:385` 起）                                    | 名字、用途、亮暗缺省值                 | README:449 列「让图表重读的属性」时漏了 `data-fve-density`；图表八色按色相命名（「蓝」「橙」），而色位是序数（D35 Q62） |
| `scripts/verify-package.mjs` 的 `OPTIONAL_GROUPS`、`NOT_BRIDGED`、`READ_OUTSIDE_THE_TOKENS` | 可选组、桥接的例外、token 块外读的变量 | 正则与计数手写（`verify-package.mjs:350-419`、`532`）                                                                   |
| `test/fixtures/presetPairs.ts`                                                              | jsdom 量的每一对                       | 与下一行是两份手写的对表                                                                                                |
| Storybook `themeContrast.tsx` 的 `TOKEN_PAIRS`                                              | 浏览器里量的每一对                     | 已经分叉：fjord 侧栏组标题 4.44:1 只有浏览器量到，单测后补（[themes.md](themes.md):517）                                |
| `src/ui/charts/theme.ts` 的 `CHART_TOKENS`、`THEME_ATTRIBUTES`                              | 图表读什么、观察什么                   | 与 README:449 各写一份                                                                                                  |
| 文档（README:509、主题指南英文版 :66）                                                      | 「预设给哪些组」                       | 指南写「four optional groups」，实际九个                                                                                |

`test/themeFiles.test.ts` 从 README 的表**解析**合同，再拿它守预设与样式表——合同的机器形式是从人读的表格里抠出来的。

### 5.2 目标：一张登记表，生成其余一切

登记表是一个纯数据的 TS 模块（暂定 `src/ui/theme/tokens.ts`，无依赖）。它只写**结构**，不写值：值仍只在 `styles.css` 与预设 CSS 里（「CSS 是运行时唯一的真相源」）。

```ts
// 示意：每个 token 一条
export const TOKENS = [
  {
    name: 'row-selected',
    tier: 'role', // 'semantic' | 'role' | 'group' | 'axis' | 'layout'
    group: 'table',
    kind: 'color', // 'color' | 'length' | 'number' | 'shadow' | 'font' | 'keyword'
    modes: 2, // 有暗色一半
    fallback: 'muted', // 不设时落到哪个 token（结构，不是值）
    preset: true, // 预设可写（进 --fvp- 层与复位）
    bridge: false, // 桥接是否从 shadcn 的同名 token 取
    chart: false, // 图表是否读它（生成 CHART_TOKENS）
    pairs: [
      { ink: 'foreground', kind: 'text' },
      { ink: 'primary', kind: 'text' },
    ], // 它作为底时要量的对
    doc: { en: 'A selected row', zh: '选中的行' },
  },
  // …
] as const;
```

由它生成（或在测试里比对）：

| 产物                                                                | 怎样生成                                                                                                                            |
| ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| README 中英两张 token 表                                            | 一个测试把登记表加上 `styles.css` 里解析出的亮暗缺省值排成表，与 README 里的比对；更新走 `-u`，与公开面清单（D29）同一个办法        |
| **FveToken** 类型（`--fve-${name}` 与 `--fve-dark-${name}` 的联合） | 由登记表推出，纯类型，从 `/ui` 导出；`tokens` prop 用它（第 7 节）                                                                  |
| `verify-package` 的规则                                             | 预设层集合、复位规则、组的「全给或全不给」、桥接的集合与例外、图表读的变量；构建时由同一份源写出 `dist/theme-tokens.json`，脚本读它 |
| 两份对表                                                            | `presetPairs.ts`（jsdom 的算术）与 `themeContrast.tsx`（浏览器的探针）都从登记表的 `pairs` 与底的列表展开，算法各自保留             |
| `CHART_TOKENS`                                                      | 登记表里 `chart: true` 的那些；`THEME_ATTRIBUTES` 与 README 里「让图表重读的属性」由登记表里 `tier: 'axis'` 的属性生成              |
| `styles.css` 的形状检查                                             | 每个登记的 token 在 token 块里恰好一行，形状是 `var(--fve-x, var(--fvp-x, …))`；没登记的 `--fve-*` 不许出现                         |

**theme-check**（修订 D30 Q50，第 10 节已定）：一个开发期的命令行（包的 `bin`，不进任何运行时入口），宿主在 CI 里跑 `wow-view-engine theme-check ./acme.css`：读登记表与宿主的 CSS，用测试夹具已有的解析器（`themeTokens.ts` 已能解析相对颜色与 `color-mix()`）算出每套、每种明暗、每种涨跌约定下的 token，量登记表里的每一对与色板三道门，并检查：写了没登记的 `--fve-*`／`--fvp-*`、预设块包在 `@layer` 里、桥接的值是 Tailwind v3 的 HSL 通道（第 7 节）。输出与 Storybook 对比度矩阵同一张表。

### 5.3 理由

- **一个事实一处写**：合同是给宿主与预设作者的，它的机器形式必须是源头，人读的表格是它的渲染；反过来从表格里抠合同，表格一改措辞测试就碎，表格漏一行合同就少一行。
- **两份对表是最危险的重复**：一份在 jsdom 里算、一份在浏览器里量，它们本应量同一组对——分叉之后，某一边漏掉的那对就只有另一边能抓到（fjord 4.44 就是这样被浏览器抓到的）。从同一张表展开，两边永远量同一组。
- **theme-check**：目的 2「证明可扩展」要求宿主写的主题能过内置主题同样的门。今天的自查是「在 Storybook 里粘贴变量」，宿主的 CI 用不上；登记表与解析器都已存在，差的只是一个入口。Q50「先看有没有宿主真的需要」的前提是「要为它另写一套」，现在不用另写。

### 5.4 代价与风险

- 生成 README 表意味着 README 的这一段不再手写；措辞改在登记表的 `doc` 里。
- `dist/theme-tokens.json` 随包发出（theme-check 读它），不在 `exports` 里，不是运行时 API；`package-check` 与 publint 要认得它。
- theme-check 是公开面的一处新增（一个 `bin`），按 D29 记进公开面清单；它依赖 culori（已是依赖）与 postcss（要从 devDependency 升为依赖，或把解析打包进 `bin`；S7 定，按「优先用现成库」）。

### 5.5 neutral 像素怎么证

S1 是纯重构：`dist/styles.css`、`themes.css`、单套文件与桥接**逐字节相同**（S1 的 PR 附前后构建产物的 diff 为空），截图不需要重跑也必然相同；S1 同时存下 `resolveTokens` 的快照，供 S2～S5 比对。

### 5.6 门怎么变

- `test/themeFiles.test.ts` 从「读 README」改成「读登记表」，并新增「README 两张表等于登记表的渲染」。
- `verify-package` 的手写正则与计数换成读 `dist/theme-tokens.json`。
- 新单测：`CHART_TOKENS` 等于登记表的图表集合；两份对表的名字集合相同（这条直接堵住 fjord 4.44 那一类分叉）。

## 6 问题五：图表只拿到颜色

### 6.1 现状

ECharts 画在 canvas 上，级联够不到，所以 `readChartTheme`（`src/ui/charts/theme.ts:254`）把 token 读回成具体的值交给它。今天读回的只有颜色与字体（`ChartTheme` 的 `palette`、`foreground`、`muted`、`border`、`ground`、`fontFamily`、`patterns`），其余全部写死：

- 网格线就是 `border`，线宽 1（`cartesianOption.ts:172-174`）——graphite、contrast 与 neutral 的网格一样。
- 字号：值标签 11（`cartesianOption.ts:235`），全局 12（`:392`）；15 个选项文件里 42 处 `fontSize: 11／12`。
- 柱的圆角 2px（`cartesianOption.ts:301`）：graphite 是方角，柱子却是圆的。
- 折线宽 2、面积不透明度 0.2（`cartesianOption.ts:332-340`）；柱宽只有上限 `BAR_MAX_WIDTH`，单系列的柱子太细（走查）；饼图扇区没有边（走查）。
- 提示框是 `bg-background … shadow-xl`（`tooltip.ts:81`），而弹层用的是 `popover` 与 `shadow-md`；暗色下提示框是页底色，比周围的卡片还暗。

### 6.2 目标

图表的整个外观从角色读出，探针按种类解析：颜色用不继承的 `background-color`（今天的做法），长度用 `width`，数字用 `opacity`，都由浏览器算，`calc()`、`min()`、相对颜色都不需要我们解析。

| 角色                                                                | 不设时                                  | 读到 `ChartTheme` 的哪里               |
| ------------------------------------------------------------------- | --------------------------------------- | -------------------------------------- |
| `chart-grid`                                                        | `border`                                | 网格线颜色                             |
| `chart-grid-width`                                                  | 1px                                     | 网格线宽                               |
| `chart-axis`                                                        | `muted-foreground`                      | 刻度、轴名                             |
| `chart-text-size`、`chart-label-size`                               | 12px、11px                              | 全局字号、值标签字号                   |
| `chart-line-width`                                                  | 2px                                     | 折线                                   |
| `chart-area-opacity`                                                | 0.2                                     | 面积图                                 |
| `chart-bar-radius`                                                  | `min(2px, 0.6 × --radius)`              | 柱的圆角                               |
| `chart-bar-min-width`、`chart-bar-max-width`                        | 无下限、48px（`BAR_MAX_WIDTH`）         | 柱宽                                   |
| `chart-slice-border`                                                | 0                                       | 扇区之间的边（颜色取底）               |
| `chart-tooltip`、`chart-tooltip-foreground`、`chart-tooltip-shadow` | `background`、`foreground`、`shadow-xl` | 图表提示框（HTML，直接用工具类读角色） |

```ts
// 示意：ChartTheme 扩成完整的图表外观；CHART_FALLBACK 同步扩，仍由测试对到 styles.css
interface ChartTheme {
  palette: readonly string[];
  foreground: string;
  ground: string;
  grid: { color: string; width: number };
  axis: { color: string };
  text: { family: string; size: number; labelSize: number };
  line: { width: number; areaOpacity: number };
  bar: { radius: number; minWidth?: number; maxWidth: number };
  slice: { border: number };
  patterns?: boolean;
  resolve(color: string): string;
  key: string;
}
```

- `chart-bar-radius` 的后备写成 `min(2px, calc(var(--radius) * 0.6))`：neutral 的 `--radius` 是 0.625rem，得 2px，与今天相同；graphite 的 `--radius` 是 0，柱子自动变方——这是「缺口修在机制里」：不给 graphite 开特例，方角风格的柱子自己就方了。
- 选项构造函数只读 `ChartTheme`，不再有字面量；`tooltipFrame` 与 `tooltip.ts` 的类名换成读角色的工具类。
- `CHART_TOKENS` 由登记表生成（第 5 节），`key` 包含新读的每一项，所以宿主只改了网格色也会重画。

### 6.3 理由

- **图表是面的一部分**：一套方角、紧凑的风格，图表若仍是圆角柱、12px 字、灰网格，就是走查说的「只换了颜色」。颜色能读回，长度与数字同样能读回，限制只是 `readChartTheme` 只写了颜色。
- **探针让浏览器算**：T1 的探针已经证明「让浏览器解析、我们只读计算值」这条路（`theme.ts` 的 `probed`）；推广到长度与数字，JS 里仍然没有任何主题逻辑。

### 6.4 代价与风险

- 15 个选项文件、42 处字号与若干处线宽、不透明度要改成读主题；现有的选项单测（按 `CHART_FALLBACK` 快照）是像素不变的第一道证据。
- 探针多读几项，每次读主题多几次 `getComputedStyle`；图表只在主题 key 变时重读，代价可忽略。
- 单系列柱太细、饼图无边、暗色迷你走势图的面积像一块——这三处是 neutral 的默认值问题（第 8 节缺陷 7），改它们会改 neutral 的像素，**不在本批**：S5 只让它们可调，neutral 的默认值要改另起一次视觉决定。

### 6.5 neutral 像素怎么证

每个图表角色的内置值等于今天的字面量；选项单测在 `CHART_FALLBACK` 下的快照逐项相同；截图里 neutral 的柱图、瀑布图、走势图逐像素相同。graphite 的柱子变方是有意的，它的截图在 S5 更新并在 PR 里并排给出。

### 6.6 门怎么变

- `test/chartTheme.test.tsx`：`CHART_FALLBACK` 的每一项对到 `styles.css` 的亮色 token 块（今天只对颜色，扩到长度与数字）；读回的 key 包含每个图表角色。
- 新单测：每个选项构造函数里没有字号、线宽、圆角的字面量（读 AST，与 `test/architecture.test.ts` 同一种做法）。

## 7 折进批次的局部项

| 项                                                                                                                                                                                                               | 做法                                                                                                                                                                                                                | 批次   |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| **弹层收不到写在包裹层上的变量**：宿主在某个 `<div style="--fve-primary: …">` 里放一块面，portal 到 `<body>` 的弹层不在这个 div 下；`useSurfaceAttributes`（`src/ui/ViewSurface.tsx:177-190`）只搬属性，不搬变量 | 面加一个 `tokens` prop，类型是 `Partial<Record<FveToken, string>>`：面把它写成自己根上的内联样式，并由 `useSurfaceAttributes` 一并写到每个弹层上。宿主要「这一块不同」时用它，而不是包裹层；包裹层的限制写进 README | S2     |
| **shadcn 桥接在 Tailwind v3 下是坏的**：v3 的 shadcn 主题把 token 写成 HSL 通道（`--primary: 222 47% 11%`），`var(--primary)` 不是颜色                                                                           | 首发只支持 Tailwind v4 的桥接，README 与指南写明；theme-check 发现通道形式的值时报出来并给一行换算的写法。没有真实的 v3 宿主之前不另出 HSL 桥接（第 10 节）                                                         | S6     |
| 私有变量占着公开前缀（`--fve-expanded-*`、`--fve-pin-left-*`、`--fve-tap-hint`）                                                                                                                                 | 改 `--_fve-*`（第 3 节）                                                                                                                                                                                            | S2     |
| 没写进文档的宿主变量（`--fve-workbench-min-height`、`--fve-record-text-max-w`）                                                                                                                                  | 进登记表（`tier: 'layout'`）与 README                                                                                                                                                                               | S1     |
| `.fve-tokens` 用通用名遮住宿主的同名变量（`--canvas`、`--control`、`--density`、`--change`、`--rise`……）                                                                                                         | 改 `--_fve-*`；shadcn 语义名保持（有意给宿主 chrome）                                                                                                                                                               | S2     |
| README 的图表八色行按色相命名，色位却是序数                                                                                                                                                                      | 登记表的 `doc` 写「第 N 个系列」，色相只在默认值一栏出现                                                                                                                                                            | S1     |
| 文档漂移：主题指南英文版 :66（「four optional groups」）、:98（`:where` 零权重的说法）、:195（一览里有「跟随系统」一条带，D45 已拆）；README:449 漏 `data-fve-density`                                           | S1 修 README（生成）；S6 按新的层与角色重写指南（中英）                                                                                                                                                             | S1、S6 |
| 宿主样板 `acme.css` 要证明新的合同                                                                                                                                                                               | 改写成：预设层只写它改的（不写 `initial`）、用 `--fve-brand` 而不是抄主色、用几个角色（选中行、表头、焦点），在浏览器里过同样的门                                                                                   | S6     |
| 分部件圆角（原列 P2，走查升为 P1）                                                                                                                                                                               | 角色 `radius-*`（第 4 节）                                                                                                                                                                                          | S3     |
| 密度的长度对宿主公开（P2）                                                                                                                                                                                       | 四个长度（表头行高、单元格上下与左右内边距、侧栏项高、面板内边距）作为 `tier: 'layout'` 的宿主变量开放，档位仍驱动它们的缺省值                                                                                      | S6     |
| 宿主自己的暗色选择器（P2）：宿主用 `[data-mode=dark]` 之类而不是 `.dark`                                                                                                                                         | `.dark` 之外接受一个宿主可配的选择器需要改 `@custom-variant`，构建期决定；先在 README 写「用 `theme` prop 钉住」的替代，等真实宿主                                                                                  | 以后   |

## 8 视觉走查结论

2026-09-25 的视觉保真走查（与本方案并行，协调者合并进来）把八套预设与宿主样板 acme 各自与它们的参照并排比较，按 1～5 打分（对照目标的相像程度）。

### 8.1 总评

**八套预设都只是换色。** 在每一套里实测相同的：工具栏按钮 28px、对话框输入框 32px、筛选条 34px；表头 13px、500、`muted` 底；复选框圆角 4px；焦点是 3px 50% 的光晕，没有实线；选中行与表头同色（都是 `muted`）；悬停只差 1.5% 的亮度。而让人认出 Ant、macOS、运维看板的，恰恰是选中色、控件高度、表头样式、焦点、菜单高亮与图表样式。

| 预设      | 分  | 与目标的主要差距                                                                                                                                                                                                                                                                              |
| --------- | --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| neutral   | 4   | —（它就是基线）                                                                                                                                                                                                                                                                               |
| slate     | 3   | 与 neutral 只差灰的色相与主色                                                                                                                                                                                                                                                                 |
| azure     | 2.5 | 对照 Ant：选中行 #e6f4ff；侧栏当前项蓝底蓝字；表头 #fafafa、600、带列分隔；控件高 32；提示框深色；菜单悬停灰、选中蓝（我们正相反）；焦点是蓝色                                                                                                                                                |
| porcelain | 3   | 对照 macOS：菜单高亮是主色填充白字；表头无底、常规字重的小字，行交替底色；搜索框描边而筛选芯片填色（不一致）；分段控件的滑块没有阴影，读反了；对话框标题不读 `title-weight`（缺陷）；控件圆角 9.6～12px 而 macOS 约 5～6px（要分部件圆角）；#F2F2F7 是 iOS 的分组底，macOS 的窗口底约 #ECECEC |
| graphite  | 2   | 只有表格不同，其余（控件高、14px 正文、指标字号）与 neutral 相同；暗色不像运维看板（参照约 #111217／#181B1F、12% 的边、#CCCCDC 的字）；图表的网格、线宽、面积与其余各套相同；4px 的复选框与方角冲突                                                                                           |
| fjord     | 3.5 | 暗色好；亮色主色 #4C6A92 太灰（参照的霜蓝 #5E81AC／#88C0D0），在预设里就能修                                                                                                                                                                                                                  |
| contrast  | 2.5 | 亮色看起来像 neutral；焦点没有达到它自己的规格（2px 实线加 2px 间隔）；选中太弱；边只有 1px                                                                                                                                                                                                   |
| brand     | 3   | 品牌色只落在很小的面积上；图表第 1 色仍是默认蓝，与品牌主色撞在一起（本方案删掉 `brand`，品牌色成为输入，第 2 节）                                                                                                                                                                            |
| acme      | 3.5 | 宿主样板；作为「合同够不够用」的证据                                                                                                                                                                                                                                                          |

### 8.2 走查要的扩展点，落到哪个角色

| 走查要的                                   | 角色（第 4、6 节）                                                                       | 默认                                                                                                  | 批次   |
| ------------------------------------------ | ---------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | ------ |
| 选中的底与字                               | `row-selected`、`row-selected-foreground`；品牌色给了时派生淡色（第 2 节）               | 走查建议 `color-mix(primary 10%)`；**neutral 保持 `muted`**（像素不变），各预设重调时设               | S3     |
| 悬停、按下的强度（≥4% 亮度差）             | `row-hover`、`control-hover`、`control-pressed`                                          | neutral 保持今天；各预设重调时设 ≥4%                                                                  | S3     |
| 焦点宽度、偏移、样式                       | `focus-width`、`focus-offset`、`focus-style`、`focus-halo`                               | 走查建议 2px 实线加 2px 偏移；**neutral 保持今天**（1px 边过 1.4.11 的 3:1，AA 成立），各预设重调时设 | S3     |
| 控件高度梯级（工具栏、筛选条、对话框一套） | `control-height`、`control-height-sm`                                                    | 28／32（筛选条 34，S3 实测它与梯级的关系）                                                            | S3     |
| 表头的底、字、字重、列分隔                 | `table-header`、`table-header-foreground`、`table-header-weight`、`table-header-divider` | 今天                                                                                                  | S3     |
| 行斑马纹                                   | `row-stripe`                                                                             | 关                                                                                                    | S3     |
| 分部件圆角                                 | `radius-card`、`radius-control`、`radius-popover`、`radius-badge`、`radius-checkbox`     | 今天的派生值                                                                                          | S3     |
| 线宽                                       | `edge-width`（只到控件边）                                                               | 1px                                                                                                   | S3     |
| 菜单高亮方式（淡色 vs 主色填充）           | `highlight`、`highlight-foreground`                                                      | `accent`                                                                                              | S3     |
| 反色的提示框                               | `tooltip`（组件）、`chart-tooltip`（图表）                                               | 各自今天的值                                                                                          | S3、S5 |
| 图表网格、坐标轴、线宽、面积、柱宽、扇区边 | 第 6 节的图表角色                                                                        | 今天                                                                                                  | S5     |
| 分段控件滑块的阴影                         | `control-thumb-shadow`                                                                   | 无                                                                                                    | S3     |
| 徽标样式（描边 vs 填色）                   | `badge-edge`、`badge-fill`                                                               | **保留边**（第 10 节）                                                                                | S3     |

P0（走查定）：弹层字体（缺陷 1，单独的 PR 已在做）、选中 token 与更强的状态、焦点——后两者都在 S3。P1：其余扩展点，以及把 azure、porcelain、graphite 调到 4 分——每套一批（S8～S13）。

### 8.3 重构之外的缺陷

这些不是结构问题，另行修，不挡本方案的批次：

1. **portal 出去的弹层在六套预设里退回衬线字体**：弹层继承的是 `<body>` 的字体，不是面的 `--fve-font-sans`。协调者已另开 PR 修。
2. **对话框标题不读 `title-weight`**（`components/dialog.tsx:125` 的 `font-medium`，`dialog-title` 不在 `styles.css:864-867` 的规则里）。它正好是 `title-weight` 角色少覆盖的一个 `data-slot`，S3 把它列进规则时一起修；若 S3 之前有人先修，就只是在那条规则里加一个选择器。
3. **记录视图查询失败时只有一条细红条压在空白上**，而面板级的错误态是好的：两者统一。
4. **指标卡的日期在每张卡上重复、14px**：降为 12px、弱字。
5. **暗色下筛选条是框里套框**。
6. **手机上记录表只放得下约 1.8 列，分页浮在屏幕中间**。
7. **图表**：单系列的柱太细；饼图扇区没有边；暗色迷你走势图的面积读成一块。S5 让它们可调；neutral 的默认值要改另起一次视觉决定（会改 neutral 的像素）。

## 9 批次

每批都小、都能单独合并，按依赖排。S1～S7 每批 neutral（实际上是**全部**预设）的截图逐像素相同，只有明说的例外；S8～S13 每批只改它那一套的截图基线，PR 里并排给出改前改后，写明目标分与走查项的对照。每批合并前真浏览器逐套、逐明暗、逐控件走查（「状态不反映即缺陷」）。

| 批                              | 内容                                                                                                                                                                                                                                              | 碰的文件                                                                                                                                                                            | 依赖   | neutral 怎么证                                                                                | 估算（人日） |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | --------------------------------------------------------------------------------------------- | ------------ |
| **S1 登记表**                   | 登记表模块；README 中英 token 表由它生成（顺带修 README:449 与色位命名）；**FveToken** 类型；`verify-package` 读 `dist/theme-tokens.json`；两份对表从登记表展开；`CHART_TOKENS` 由它生成；两个没写进文档的布局变量进表；存下 `resolveTokens` 快照 | `src/ui/theme/`、`scripts/verify-package.mjs`、`test/fixtures/presetPairs.ts`、Storybook `themeContrast.tsx`、`charts/theme.ts`、两个 README、`test/themeFiles.test.ts`             | —      | 构建产物逐字节相同                                                                            | 2            |
| **S2 三层**                     | 预设写 `--fvp-*`、只写它改的；复位规则（`@layer fve-reset`，由登记表生成）；桥接写预设层；私有变量与 `.fve-tokens` 上的通用名改 `--_fve-*`；`tokens` prop；密度推荐改写到预设层                                                                   | `styles.css`、`src/themes/*.css`、`shadcn-bridge.css`、`verify-package.mjs`、`themeTokens.ts`、`ViewSurface.tsx`、`ViewExpansion.tsx`、`record/sticky.ts`、`EChart.tsx`、`acme.css` | S1     | 每套、每种明暗与约定的 token 快照相同；截图 51 张全部相同                                     | 2.5          |
| **S3 角色**                     | 第 4 节的角色（含走查 P0：选中、状态强度、焦点）；四个可选组并入；`data-slot` 规则；组件在元素上补 `data-slot`；对话框标题进 `title-weight`；新的截图故事（菜单高亮、遮罩、提示框、焦点 2px）                                                     | `styles.css`、`variants.tsx`、`record/sticky.ts`、登记表、`verify-package.mjs`、测试夹具、Storybook                                                                                 | S2     | 八套截图全部相同（角色不设即今天）；新增的截图是新文件，不改已有的                            | 3            |
| **S4 品牌是输入**               | 先把 Storybook 的全局品牌色挪进「品牌色」故事；派生式进 `styles.css`；各预设的夹子边界；图表第 1 色的宿主开关；删 `brand` 预设与它的故事，改成「任一预设 + 品牌色」的故事；扫描扩到每一套                                                         | `styles.css`、`src/themes/*.css`、`.storybook/preview.css`、`test/brandPreset.test.ts`、`BUILT_IN_PRESETS`、README                                                                  | S3     | 截图里没有品牌色，全部相同；「没有品牌色就是这套预设」对每套成立                              | 2            |
| **S5 图表读角色**               | 第 6 节：`ChartTheme` 扩展、探针按种类、15 个选项文件去字面量、提示框读角色                                                                                                                                                                       | `src/ui/charts/*`、`styles.css`、登记表                                                                                                                                             | S3     | 选项快照在 `CHART_FALLBACK` 下相同；neutral 截图相同。**例外**：graphite 的柱子变方，基线更新 | 2.5          |
| **S6 宿主文档与样板**           | 主题指南（中英）按三层、角色、品牌输入重写（修 :66、:98、:195）；README 的主题一节；`acme.css` 证明新合同；Tailwind v4 桥接说明；密度长度对宿主开放；快速上手页的「我的品牌该选哪套」表去掉 `brand` 一行                                          | `documentation/docs/{en,zh}/guide/typescript/view-engine-theming.md`、两个 README、`host-theme/acme.css`、`styles.css`（密度长度）                                                  | S4、S5 | 文档不动像素；`acme.css` 的故事不在截图里                                                     | 1.5          |
| **S7 theme-check**              | 包的 `bin`；读 `dist/theme-tokens.json` 与宿主 CSS；复用夹具的解析与量对；检查层外预设、HSL 通道、未登记的变量；公开面清单加一行                                                                                                                  | `scripts/`、`package.json`、`typescript/wow-view-engine/test/surface/`、README                                                                                                      | S1、S4 | 不动 CSS                                                                                      | 1.5          |
| **S8 azure 重调**（目标 4）     | 走查的 azure 各项：选中 #e6f4ff、侧栏当前项、表头 #fafafa／600／列分隔、控件高 32、深色提示框、菜单悬停灰选中蓝、蓝色焦点（`ring` 跟主色要量过每种底）；`content` 白、`background` 灰                                                             | `src/themes/azure.css`、基线                                                                                                                                                        | S3、S5 | 只更新 azure 的基线                                                                           | 1            |
| **S9 porcelain 重调**（目标 4） | 菜单高亮主色填充白字；表头无底、常规字重；行交替底色；搜索框与筛选芯片一致；滑块阴影；控件圆角 5～6px、卡片约 10px；窗口底改 macOS 的约 #ECECEC（不是 iOS 的分组底）                                                                              | `src/themes/porcelain.css`、基线                                                                                                                                                    | S3、S5 | 只更新 porcelain 的基线                                                                       | 1            |
| **S10 graphite 重调**（目标 4） | 按第 10 节定的参照：暗色以运维监控看板为准（约 #111217／#181B1F、12% 的边、#CCCCDC 的字），亮色保留方角灰阶；控件高、正文与指标字号走紧凑一档；方角复选框；图表网格与线宽；2px 内描边焦点                                                         | `src/themes/graphite.css`、基线、[themes.md](themes.md) 3.4.3                                                                                                                       | S3、S5 | 只更新 graphite 的基线                                                                        | 1            |
| **S11 contrast 重调**（目标 4） | 焦点 2px 实线加 2px 间隔（兑现 3.4.5 的规格）；更强的选中；控件边 2px（`edge-width`）；亮色与 neutral 拉开                                                                                                                                        | `src/themes/contrast.css`、基线                                                                                                                                                     | S3     | 只更新 contrast 的基线                                                                        | 0.5          |
| **S12 fjord 重调**（目标 4）    | 亮色主色向霜蓝靠（#5E81AC 一族，按带底上作字 ≥4.5 取最近的一档）；选中与状态强度                                                                                                                                                                  | `src/themes/fjord.css`、基线                                                                                                                                                        | S3     | 只更新 fjord 的基线                                                                           | 0.5          |
| **S13 slate 重调**（目标 3.5）  | 选中与状态强度、焦点；与 neutral 在层次上拉开一档                                                                                                                                                                                                 | `src/themes/slate.css`、基线                                                                                                                                                        | S3     | 只更新 slate 的基线                                                                           | 0.5          |

合计约 19.5 人日。S1→S2→S3 是关键路径；S4、S5 在 S3 之后可以并行（一个碰 `styles.css` 的品牌段与预设，一个碰图表），S8～S13 在各自依赖之后并行（每批只碰一个预设文件与它的基线），按控制 CPU 负载的惯例同时最多两路。截图基线在 Linux 容器里截，本机与 CI 同一条路（D45）。

每批合并后：更新 [todo.md](todo.md) 与 [progress.md](progress.md) 的暂停点；一批若改了本页的设计，就在同一个 PR 里改本页。

## 10 已定（2026-09-25，按推荐）

用户 2026-09-25：「基于第一性原理，按你推荐。」下面每一条是选定的做法与一句理由；记为 [D46](decisions.md#d46-主题架构重构五条结构一张登记表2026-09-25)。

1. **行斑马纹进角色层，可选，默认关。** 理由：它是 macOS 表格与部分运维表格的辨识特征，合同必须能说；但斑马纹与悬停、选中争同一组亮度差，默认开会让 neutral 变样、也让每套都要多量一对，所以只给要的预设开。
2. **`brand` 预设删掉，不留别名；宿主在任何预设上写 `--fve-brand`。** 理由：首发前改名不欠兼容；别名会让「品牌色是一套风格」这个错误的模型继续出现在文档与补全里。
3. **graphite 保留用途（紧凑、方角的运维看板），参照写明：暗色以开源监控看板（Grafana 一类）为准，亮色沿用 IBM 系企业设计系统的方角灰阶。** 理由：用途决定参照——运维用户每天看的是监控看板，那是他们认得出的样子；而那类看板以暗色为主，亮色一半没有公认的样子，沿用现在的方角灰阶最稳。参照写进 [themes.md](themes.md) 3.4.3。
4. **图表第 1 色跟品牌色是宿主的开关（`--fve-brand-chart: 1`），默认关。** 理由：品牌色相与相邻色位的色觉间距对任意品牌色证明不了，不能替每个宿主默认打开；开了就像覆盖 `--fve-chart-*` 一样由宿主（和 theme-check）负责量。
5. **徽标的边默认保留**（用户 2026-09-25 另行拍板，按推荐）。理由：去掉边，淡色徽标在选中行上只剩 1.16～1.22:1，低于「在行上仍是一个徽标」的 1.5:1（P-21）。预设可以把 `badge-edge` 设成透明改用填色，前提是这一对在它自己的 `row-selected` 上仍过 1.5:1——门守着。
6. **做 theme-check（修订 D30 Q50）**，开发期的 `bin`，S7。理由：登记表与解析器都已存在，差一个入口；目的 2 要求宿主的主题能过同样的门，CI 里跑得到才算。
7. **钉住的预设不再压过宿主的 `:root`**：宿主的 `--fve-*` 在任何嵌套、任何钉住下都赢。理由：这是 README 与指南已经承诺的规则；宿主要例外就把覆盖写窄，这是它在自己的两个决定之间取舍。
8. **shadcn 桥接首发只支持 Tailwind v4**，README 写明，theme-check 认出 v3 的 HSL 通道并给换算写法；不另出 HSL 桥接。理由：没有真实的 v3 宿主，不为没有使用者的形态加文件。
9. **neutral 的选中、悬停、焦点保持现状。** 理由：「每批 neutral 像素不变」是硬约束，neutral 的焦点在 AA 上成立；走查建议的更强默认值由各预设在重调批里设。neutral 要不要变强另起一次视觉决定，更新基线。
10. **1.2 的边界**：分部件圆角、控件高度梯级、控件边宽、菜单高亮与徽标样式（作为颜色角色）进主题；组件形状变体、网络字体、毛玻璃、动效、存下的几何仍不进（4.6）。理由见 4.6：它们都是「同一个量分部件给值」或「用哪对颜色」，不是结构。
11. **品牌色给了时，选中行派生品牌淡色。** 理由：选中是品牌最该出现的地方之一（走查：品牌色只落在很小的面积上）；派生经扫描守线。
12. **`tokens` prop，类型由登记表推出。** 理由：弹层 portal 出去就不在包裹层下，属性能搬、变量不能搬；给一个面级的入口，比让宿主记住「别写在包裹层上」可靠。
13. **登记表的 JSON 随包发出**（`dist/theme-tokens.json`，不进 `exports`）。理由：theme-check 与 `verify-package` 都读它，它是合同的机器形式，宿主工具也能读。

## 11 考虑过、没选的

- **用 `@scope` 加不继承的 `@property` 做层**：预设值只声明在边界上、不继承，用 scope 的「近者胜」处理嵌套，可以不要复位规则。没选：桥接要把 `--fvp-*` 指向宿主的 `--primary`，而在边界上 `--primary` 是我们自己的 token，会成环；另外它把机制压在三个较新的特性上，一个零权重的复位规则就能做到同样的事。
- **登记表连值一起写、由它生成 CSS**：值会有两处（TS 与 CSS），或者 CSS 变成生成物、宿主读不到带理由的源。没选：CSS 是值的真相源，登记表只写结构，测试检查两者对得上。
- **保留 `brand` 预设、另加「品牌色输入」**：两条路做同一件事，文档要解释何时用哪个。没选（第 10 节第 2 条）。
- **每套预设各写一份派生式**（审查最初的写法）：七份几乎相同的 `oklch(from …)`，而且派生在预设元素上替换，品牌色必须挂在同一元素或更外层。没选：派生一处、边界各给，品牌色挂在哪都对。
- **JS 主题对象、`createTheme()`、ThemeProvider**：固定前提排除（第二个真相源）。
