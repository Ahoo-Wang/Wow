/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { TokenName } from './tokens.js';

/** One phrase, in both of the READMEs' languages. */
export interface Words {
  readonly en: string;
  readonly zh: string;
}

/**
 * What the README says of one token: its role, and — where the built-in
 * value is not a literal or another token the stylesheet names, so the
 * table cannot read it off `styles.css` — how that default reads. A default
 * given for light alone is the same in dark.
 */
export interface TokenDoc {
  readonly role: Words;
  readonly light?: Words;
  readonly dark?: Words;
}

const same = (text: string): Words => ({ en: text, zh: text });

const UNSET: Words = { en: 'unset', zh: '不设' };

/** A token's role, in both languages, and nothing more to say. */
const said = (en: string, zh: string): TokenDoc => ({ role: { en, zh } });

const OWN_CONTROL: Words = {
  en: "unset: each control's own",
  zh: '不设：各控件原样',
};

const slot = (n: number): TokenDoc => ({
  role: {
    en: `Chart colour slot ${n}: the ${ORDINALS[n - 1]} series`,
    zh: `图表第 ${n} 个色位：第 ${n} 个系列`,
  },
});

const ORDINALS = [
  'first',
  'second',
  'third',
  'fourth',
  'fifth',
  'sixth',
  'seventh',
  'eighth',
];

const lift = (step: string, what: Words): TokenDoc => ({
  role: what,
  light: {
    en: `Tailwind's \`shadow-${step}\``,
    zh: `Tailwind 的 \`shadow-${step}\``,
  },
});

/**
 * One bound a preset holds the brand colour to (theme-architecture.md 2.2).
 * Its default is read off the stylesheet's derivation; a bound with none is
 * unset, and what it bounds is not derived.
 */
const bound = (role: Words, unset = false): TokenDoc =>
  unset ? { role, light: UNSET } : { role };

/**
 * The words of the README's token tables, one entry per registered token —
 * a catalogue beside `tokens.ts`, so the runtime that reads the registry
 * does not carry them. The type makes it whole: a token without its words
 * does not compile.
 */
export const TOKEN_DOCS: Readonly<Record<TokenName, TokenDoc>> = {
  background: { role: { en: 'Surface behind everything', zh: '整体底色' } },
  foreground: { role: { en: 'Default text', zh: '默认文字' } },
  card: { role: { en: 'Card and panel surface', zh: '卡片与面板底色' } },
  'card-foreground': { role: { en: 'Text on cards', zh: '卡片上的文字' } },
  popover: { role: { en: 'Popup surface', zh: '弹层底色' } },
  'popover-foreground': { role: { en: 'Text in popups', zh: '弹层内文字' } },
  primary: { role: { en: 'Primary action fill', zh: '主操作填充' } },
  'primary-foreground': {
    role: { en: 'Text on primary', zh: '主操作上的文字' },
  },
  secondary: { role: { en: 'Secondary action fill', zh: '次操作填充' } },
  'secondary-foreground': {
    role: { en: 'Text on secondary', zh: '次操作上的文字' },
  },
  muted: { role: { en: 'Muted surface', zh: '弱化底色' } },
  'muted-foreground': { role: { en: 'Secondary text', zh: '次要文字' } },
  accent: { role: { en: 'Hover and selected fill', zh: '悬停与选中填充' } },
  'accent-foreground': {
    role: { en: 'Text on accent', zh: '强调态上的文字' },
  },
  sidebar: { role: { en: 'Navigation column ground', zh: '导航列底色' } },
  'sidebar-foreground': {
    role: { en: 'Text in the navigation column', zh: '导航列上的文字' },
  },
  'sidebar-accent': {
    role: { en: 'Hovered row in the column', zh: '导航列的悬停项' },
  },
  'sidebar-accent-foreground': {
    role: { en: 'Text on a hovered row', zh: '悬停项上的文字' },
  },
  'sidebar-border': { role: { en: "The column's edge", zh: '导航列的边' } },
  destructive: { role: { en: 'Danger and delete', zh: '危险与删除' } },
  success: { role: { en: 'Positive outcome', zh: '成功' } },
  warning: {
    role: { en: 'Needs attention, not blocking', zh: '需要注意、不阻塞' },
  },
  border: { role: { en: 'Borders and dividers', zh: '边框与分隔线' } },
  input: { role: { en: 'Input and control borders', zh: '输入与控件边框' } },
  ring: { role: { en: 'Focus ring', zh: '焦点环' } },
  'destructive-foreground': {
    role: {
      en: 'Text on a destructive fill (derived)',
      zh: '危险填充上的文字（推导）',
    },
  },
  'quiet-foreground': {
    role: {
      en: 'The quiet half of a summary row (derived)',
      zh: '汇总行里弱的那一半（推导）',
    },
    light: { en: '`foreground` at 70%', zh: '`foreground` 的 70%' },
  },
  'pin-shadow': {
    role: {
      en: "The soft edge of a pinned column; the mode's, never a preset's",
      zh: '冻结列的柔边；归明暗，不归预设',
    },
  },
  'chart-1': slot(1),
  'chart-2': slot(2),
  'chart-3': slot(3),
  'chart-4': slot(4),
  'chart-5': slot(5),
  'chart-6': slot(6),
  'chart-7': slot(7),
  'chart-8': slot(8),
  radius: {
    role: {
      en: 'Corner radius, the rest scale off it',
      zh: '圆角基准，其余档位由它换算',
    },
  },
  'text-ui': {
    role: {
      en: 'The one size under the body text',
      zh: '正文之下唯一的那一档字号',
    },
  },
  'font-sans': {
    role: { en: 'The type, a system font stack', zh: '字体，一条系统字体栈' },
    light: { en: "unset: the page's", zh: '不设：页面的' },
  },
  'chart-patterns': {
    role: {
      en: 'Patterns over the chart series: `on`, `off`, or unset / `auto` to follow the reader\'s "increase contrast"',
      zh: '图表系列上的花纹：`on`、`off`，或不设／`auto` 跟随读者的「提高对比度」',
    },
    light: UNSET,
  },
  brand: {
    role: {
      en: "The brand colour, on any preset: the primary, the tints of `accent`, `sidebar-accent` and a selected row, and the focus ring where the preset bounds it take its hue, each held to the preset's lines",
      zh: '品牌色，任何预设都接受：主色、`accent`、`sidebar-accent` 与选中行的淡色，以及预设给了边界时的焦点环都取它的色相，各按该预设的线收住',
    },
    light: UNSET,
    dark: same('`brand`'),
  },
  'brand-chart': {
    role: {
      en: "`1`: the first chart slot takes the brand's hue at the lightness and chroma the preset tuned it to (the host measures the palette then)",
      zh: '`1`：图表第 1 色取品牌色相，亮度与彩度保留预设调好的（此时色板由宿主负责量）',
    },
    light: UNSET,
  },
  'brand-l-min': bound({
    en: "The primary's lower lightness bound: a brand darker than this is lifted to it",
    zh: '主色亮度的下限：比它暗的品牌色被提到这里',
  }),
  'brand-l-max': bound({
    en: "The primary's upper lightness bound: a brand lighter than this is taken down to it",
    zh: '主色亮度的上限：比它亮的品牌色被压到这里',
  }),
  'brand-c-max': bound({
    en: "The primary's and the ring's chroma ceiling",
    zh: '主色与焦点环的彩度上限',
  }),
  'brand-ring-l-min': bound(
    {
      en: "The focus ring's lower lightness bound; with both ring bounds unset the ring is not derived",
      zh: '焦点环亮度的下限；两个焦点边界都不设时焦点环不跟品牌色',
    },
    true,
  ),
  'brand-ring-l-max': bound(
    {
      en: "The focus ring's upper lightness bound",
      zh: '焦点环亮度的上限',
    },
    true,
  ),
  'brand-accent-lc': bound({
    en: 'The lightness and chroma (two numbers) the brand is set at for `accent`',
    zh: '`accent` 取品牌色相时的亮度与彩度（两个数）',
  }),
  'brand-sidebar-accent-lc': bound({
    en: 'The lightness and chroma the brand is set at for `sidebar-accent`',
    zh: '`sidebar-accent` 取品牌色相时的亮度与彩度',
  }),
  'brand-row-selected-lc': bound({
    en: 'The lightness and chroma the brand is set at for a selected row',
    zh: '选中行取品牌色相时的亮度与彩度',
  }),
  'brand-chart-1-lc': bound({
    en: "The lightness and chroma of the first chart slot under `brand-chart` — the preset's own first slot's",
    zh: '`brand-chart` 打开时图表第 1 色的亮度与彩度——即预设自己第 1 色的',
  }),
  'preset-density': {
    role: {
      en: "The density a preset recommends: `-1`, `0` or `1` (a preset's; a host sets `data-fve-density`)",
      zh: '预设推荐的密度：`-1`、`0` 或 `1`（归预设；宿主用 `data-fve-density`）',
    },
    light: UNSET,
  },
  rise: {
    role: { en: 'A rise, by its direction', zh: '上升，按方向' },
    light: {
      en: '`success` (see [change colours](#change-colours-rising-and-falling))',
      zh: '`success`（见[涨跌色](#涨跌色升与降)）',
    },
    dark: same('`success`'),
  },
  fall: {
    role: { en: 'A fall, by its direction', zh: '下降，按方向' },
    light: same('`destructive`'),
    dark: same('`destructive`'),
  },
  'shadow-sm': lift('sm', {
    en: 'The low lift: a raised card',
    zh: '低的一档浮起：浮起的卡片',
  }),
  'shadow-md': lift('md', {
    en: 'The middle lift: a popup',
    zh: '中的一档浮起：弹层',
  }),
  'shadow-lg': lift('lg', {
    en: 'The high lift: a dragged panel',
    zh: '高的一档浮起：拖动中的面板',
  }),
  canvas: {
    role: {
      en: "The grouped ground a board and a host's card-laid page stand on (`bg-canvas`)",
      zh: '分组底：看板与宿主按卡片排的页面站在它上面（`bg-canvas`）',
    },
  },
  content: {
    role: {
      en: 'The ground rows and a result are written on',
      zh: '行与结果写在上面的底',
    },
  },
  'card-edge': {
    role: {
      en: 'The ring round a card: a board panel, a record card',
      zh: '卡片的一圈边：看板面板、记录卡片',
    },
    light: { en: '`foreground` at 10%', zh: '`foreground` 的 10%' },
  },
  'card-shadow': {
    role: {
      en: "A card's lift off what it sits on",
      zh: '卡片离开底的浮起',
    },
  },
  scrim: {
    role: {
      en: 'What dims the page behind a dialog or a sheet',
      zh: '对话框与抽屉背后压暗页面的遮罩',
    },
  },
  'table-header': {
    role: { en: "A table's header band", zh: '表格的表头带' },
  },
  'table-header-foreground': {
    role: { en: 'The words on the header band', zh: '表头带上的文字' },
  },
  'table-header-weight': {
    role: { en: "The header's weight", zh: '表头的字重' },
  },
  'table-header-divider': {
    role: {
      en: "A line between the header's columns (`transparent`: none)",
      zh: '表头列与列之间的分隔线（`transparent`：没有）',
    },
  },
  totals: {
    role: {
      en: "A table's totals band: the summary rows, an analysis's totals",
      zh: '表格的合计带：汇总行、分析的合计行',
    },
  },
  'row-selected': {
    role: {
      en: 'A selected row, a pressed group',
      zh: '选中的行、按下的分组',
    },
  },
  'row-selected-foreground': {
    role: { en: 'The words on a selected row', zh: '选中行上的文字' },
  },
  'row-hover': {
    role: { en: 'A hovered row (derived)', zh: '悬停的行（推导）' },
    light: {
      en: '`muted` halfway into `background`',
      zh: '`muted` 与 `background` 各半',
    },
  },
  'row-stripe': {
    role: {
      en: "Every other row's ground (off: the rows' own)",
      zh: '隔行的底（关：行自己的底）',
    },
  },
  highlight: {
    role: {
      en: 'The item a menu, a select or a combobox has under the keyboard or the pointer',
      zh: '菜单、选择框、组合框里键盘或指针所在的那一项',
    },
  },
  'highlight-foreground': {
    role: { en: 'The words on that item', zh: '那一项上的文字' },
  },
  'nav-current': {
    role: {
      en: 'The view on screen in the view list',
      zh: '视图列表里正在看的那一个',
    },
  },
  'nav-current-foreground': {
    role: { en: 'Its words', zh: '它的文字' },
  },
  'control-hover': {
    role: {
      en: 'A button or a toggle under the pointer',
      zh: '指针下的按钮或切换',
    },
    light: OWN_CONTROL,
    dark: OWN_CONTROL,
  },
  'control-pressed': {
    role: { en: 'A toggle pressed', zh: '按下的切换' },
    light: { en: 'unset: `muted`', zh: '不设：`muted`' },
    dark: { en: 'unset: `muted`', zh: '不设：`muted`' },
  },
  'focus-width': {
    role: {
      en: "The width of a focused control's outline",
      zh: '获得焦点的控件的轮廓宽度',
    },
    light: {
      en: "unset: no outline, the registry's edge and halo",
      zh: '不设：没有轮廓，用 registry 的边与光晕',
    },
  },
  'focus-offset': {
    role: {
      en: "How far that outline stands off the control's edge",
      zh: '那道轮廓离控件边的距离',
    },
  },
  'focus-style': {
    role: {
      en: "That outline's style: `solid`, `dashed`, `double`…",
      zh: '那道轮廓的样式：`solid`、`dashed`、`double`……',
    },
  },
  'focus-halo': {
    role: {
      en: 'The halo round a focused control (`transparent`: none)',
      zh: '获得焦点的控件周围的光晕（`transparent`：没有）',
    },
    light: { en: '`ring` at 50%', zh: '`ring` 的 50%' },
  },
  control: {
    role: {
      en: 'The rest fill of a control its words or icons name: a filter chip, a segmented control',
      zh: '以文字或图标自明的控件的静止填色：筛选条、分段控件',
    },
    light: OWN_CONTROL,
    dark: OWN_CONTROL,
  },
  'control-edge': {
    role: {
      en: "That control's edge (a chip holding a text box keeps `input`)",
      zh: '这类控件的边（装着输入框的筛选条仍用 `input`）',
    },
    light: OWN_CONTROL,
    dark: OWN_CONTROL,
  },
  'control-thumb': {
    role: {
      en: "A segmented control's pressed item, the thumb on its track",
      zh: '分段控件按下的那一项，轨道上的滑块',
    },
    light: { en: 'unset: `muted`', zh: '不设：`muted`' },
    dark: { en: 'unset: `muted`', zh: '不设：`muted`' },
  },
  'control-thumb-shadow': {
    role: {
      en: 'The lift of that thumb off its track',
      zh: '滑块离开轨道的浮起',
    },
  },
  'control-height': {
    role: {
      en: "A control's height: a button, a text box, a select, a filter chip's controls",
      zh: '控件的高度：按钮、输入框、选择框、筛选条里的控件',
    },
  },
  'control-height-sm': {
    role: {
      en: "A small control's height: the toolbars' buttons",
      zh: '小控件的高度：工具栏的按钮',
    },
  },
  'edge-width': {
    role: {
      en: "The width of a control's edge (a divider stays 1px)",
      zh: '控件边的宽度（分隔线仍是 1px）',
    },
  },
  'badge-edge': {
    role: {
      en: "How much of its tone a toned badge's edge takes",
      zh: '带色徽标的边取它的色调多少',
    },
  },
  'badge-fill': {
    role: {
      en: "How much of its tone a toned badge's wash takes",
      zh: '带色徽标的底取它的色调多少',
    },
  },
  'radius-card': {
    role: { en: "A card's and a dialog's corner", zh: '卡片与对话框的圆角' },
    light: { en: '`radius` × 1.4', zh: '`radius` × 1.4' },
  },
  'radius-control': {
    role: {
      en: "A control's corner (a small control's is 0.8 of it, at most 12px)",
      zh: '控件的圆角（小控件取它的 0.8，最多 12px）',
    },
  },
  'radius-popover': {
    role: { en: "A popup's corner", zh: '弹层的圆角' },
  },
  'radius-badge': {
    role: { en: "A badge's corner", zh: '徽标的圆角' },
    light: { en: '`radius` × 2.6', zh: '`radius` × 2.6' },
  },
  'radius-checkbox': {
    role: { en: "A checkbox's corner", zh: '复选框的圆角' },
  },
  'title-weight': {
    role: {
      en: "The weight of a view's, a card's and a dialog's title",
      zh: '视图、卡片与对话框标题的字重',
    },
  },
  'strong-weight': {
    role: {
      en: 'The weight of what is strong beside its text: a table header, the totals',
      zh: '比周围文字更重的那些的字重：表头、合计',
    },
  },
  tooltip: {
    role: { en: "A tooltip's ground", zh: '提示框的底' },
  },
  'tooltip-foreground': {
    role: { en: 'The words in a tooltip', zh: '提示框里的文字' },
  },
  'chart-grid': said(
    "A chart's gridlines and axis rules",
    '图表的网格线与轴线',
  ),
  'chart-grid-width': said(
    "The width of a chart's gridlines",
    '图表网格线的宽度',
  ),
  'chart-axis': said(
    "A chart's quiet text: ticks, axis titles, a scale's ends, the names beside its marks",
    '图表里弱一级的字：刻度、轴名、色阶两端、图形旁的名称',
  ),
  'chart-text-size': {
    ...said(
      "The size of a chart's text: ticks, axis titles, names",
      '图表文字的字号：刻度、轴名、名称',
    ),
    light: { en: '`text-ui` − 1px (12px)', zh: '`text-ui` − 1px（12px）' },
  },
  'chart-label-size': {
    ...said(
      "The size of a value label on a mark, a step under the chart's text",
      '标在图形上的数值的字号，比图表文字小一级',
    ),
    light: { en: '`text-ui` − 2px (11px)', zh: '`text-ui` − 2px（11px）' },
  },
  'chart-line-width': said(
    "The width of a line chart's line (a derived line is ¾ of it)",
    '折线的宽度（算出的系列取它的 ¾）',
  ),
  'chart-area-opacity': said(
    "How opaque the fill under an area chart's line is",
    '面积图线下填色的不透明度',
  ),
  'chart-bar-radius': {
    ...said(
      "The corner of a bar's end (and of a funnel's stage, a heatmap's cell)",
      '柱末端的圆角（漏斗的级、热力图的格同样）',
    ),
    light: {
      en: '`radius` × 0.6, at most 2px',
      zh: '`radius` × 0.6，最多 2px',
    },
  },
  'chart-bar-min-width': {
    ...said('The narrowest a bar is drawn', '柱最窄画多宽'),
    light: {
      en: 'unset: as narrow as the plot makes it',
      zh: '不设：随绘图区',
    },
  },
  'chart-bar-max-width': said('The widest a bar is drawn', '柱最宽画多宽'),
  'chart-slice-border': said(
    "The seam between two slices of a pie, in the chart's ground",
    '饼图扇区之间的缝，颜色取图表的底',
  ),
  'chart-tooltip': said("A chart tooltip's ground", '图表提示框的底'),
  'chart-tooltip-foreground': said(
    'The numbers in a chart tooltip',
    '图表提示框里的数',
  ),
  'chart-tooltip-shadow': said(
    'The lift of a chart tooltip',
    '图表提示框的浮起',
  ),
  'popup-z-index': {
    role: {
      en: 'The stacking level every popup is portalled at',
      zh: '每个 portal 出去的弹层所在的层级',
    },
  },
  'record-table-max-h': {
    role: {
      en: 'The height a record or analysis table stops at and scrolls inside (`size="content"`)',
      zh: '记录表格与分析表格的最大高度，超出即在表内滚动（`size="content"`）',
    },
  },
  'record-text-max-w': {
    role: {
      en: 'How wide a `text` cell grows before it wraps',
      zh: '`text` 单元格换行之前最多多宽',
    },
  },
  'workbench-min-height': {
    role: {
      en: 'The floor under a workbench in a container of no definite height',
      zh: '容器没有确定高度时，工作台的最低高度',
    },
  },
};
