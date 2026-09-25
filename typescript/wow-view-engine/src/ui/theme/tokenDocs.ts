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
  'row-hover': {
    role: { en: 'A hovered row (derived)', zh: '悬停的行（推导）' },
    light: {
      en: '`muted` halfway into `background`',
      zh: '`muted` 与 `background` 各半',
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
      en: 'The one colour the `brand` preset derives its primary and tints from',
      zh: '`brand` 预设派生主色与淡色所用的那一个颜色',
    },
    light: UNSET,
    dark: same('`brand`'),
  },
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
  'title-weight': {
    role: {
      en: "The weight of a view's and a card's title",
      zh: '视图标题与卡片标题的字重',
    },
  },
  'popup-z-index': {
    role: {
      en: 'The stacking level every popup is portalled at',
      zh: '每个 portal 出去的弹层所在的层级',
    },
  },
  'record-table-max-h': {
    role: {
      en: 'The height a record table stops at and scrolls inside (`size="content"`)',
      zh: '记录表格的最大高度，超出即在表内滚动（`size="content"`）',
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
