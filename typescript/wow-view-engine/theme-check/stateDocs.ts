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

import type { TokenDoc, Words } from './tokenDocs.js';

/**
 * The README's words for the roles that mark a state — a highlighted item,
 * a chosen one, the view on screen, a control under the pointer or pressed,
 * an outline button under the pointer — and their links
 * (ui/theme.md「角色：引擎自己的面」), beside `tokenDocs.ts` so that catalogue
 * stays one screenful of files.
 */

/** A control that keeps its own value, unset: a fill, an edge, a hover. */
export const OWN_CONTROL: Words = {
  en: "unset: each control's own",
  zh: '不设：各控件原样',
};

/**
 * A link (ui/theme.md「链接：角色跟着面上解析出的 token」): the share of a resolved token a role
 * is drawn in, and unset, not linked.
 */
const linked = (role: string, to: string): TokenDoc => ({
  role: {
    en: `How much of the resolved \`${to}\` \`${role}\` is drawn in: \`100%\` is \`${to}\` itself, following a brand colour and the mode`,
    zh: `\`${role}\` 取解析后的 \`${to}\` 多少：\`100%\` 就是 \`${to}\` 本身，随品牌色与明暗`,
  },
  light: { en: 'unset: not linked', zh: '不设：不链接' },
});

/** A strength: a share, larger on a dark ground. */
const strength = (en: string, zh: string, light: string, dark: string) =>
  ({
    role: { en, zh },
    light: { en: `\`${light}\``, zh: `\`${light}\`` },
    dark: { en: `\`${dark}\``, zh: `\`${dark}\`` },
  }) satisfies TokenDoc;

export const STATE_DOCS = {
  'state-hover': strength(
    'How much of the ink a thing under the pointer takes: a row, a button, a toggle',
    '指针下的东西取多少墨色：行、按钮、切换',
    '3%',
    '6%',
  ),
  'state-pressed': strength(
    'How much of the ink a pressed control takes',
    '按下的控件取多少墨色',
    '7%',
    '12%',
  ),
  'state-selected': strength(
    'How much of the primary a selected row takes',
    '选中的行取多少主色',
    '7%',
    '12%',
  ),
  highlight: {
    role: {
      en: 'The item a menu, a select or a combobox has under the keyboard or the pointer',
      zh: '菜单、选择框、组合框里键盘或指针所在的那一项',
    },
  },
  'highlight-foreground': {
    role: { en: 'The words on that item', zh: '那一项上的文字' },
  },
  'highlight-link': linked('highlight', 'primary-fill'),
  'highlight-foreground-link': linked(
    'highlight-foreground',
    'primary-fill-foreground',
  ),
  'item-selected': {
    role: {
      en: 'The item a menu, a select or a combobox holds chosen',
      zh: '菜单、选择框、组合框里已选中的那一项',
    },
  },
  'item-selected-foreground': {
    role: { en: 'The words on that item', zh: '那一项上的文字' },
  },
  'item-selected-weight': {
    role: { en: 'The weight of those words', zh: '那些文字的字重' },
    light: {
      en: "unset: the item's own",
      zh: '不设：那一项原样',
    },
  },
  'item-selected-link': linked('item-selected', 'row-selected'),
  'nav-current': {
    role: {
      en: 'The view on screen in the view list',
      zh: '视图列表里正在看的那一个',
    },
  },
  'nav-current-foreground': {
    role: { en: 'Its words', zh: '它的文字' },
  },
  'nav-current-edge': {
    role: { en: 'Its edge', zh: '它的边' },
  },
  'nav-current-shadow': {
    role: { en: 'Its lift off the column', zh: '它离开侧栏的浮起' },
  },
  'nav-current-link': linked('nav-current', 'row-selected'),
  'nav-current-foreground-link': linked('nav-current-foreground', 'primary'),
  'control-hover': {
    role: {
      en: 'A button or a toggle under the pointer',
      zh: '指针下的按钮或切换',
    },
    light: {
      en: "`foreground` at `state-hover`, into the control's fill",
      zh: '`foreground` 按 `state-hover` 混进控件的填色',
    },
  },
  'control-pressed': {
    role: { en: 'A toggle pressed', zh: '按下的切换' },
    light: {
      en: "`foreground` at `state-pressed`, into the control's fill",
      zh: '`foreground` 按 `state-pressed` 混进控件的填色',
    },
  },
  'outline-hover-edge': {
    role: {
      en: 'The edge of an outline button under the pointer',
      zh: '指针下的描边按钮的边',
    },
    light: { en: 'unset: `border`', zh: '不设：`border`' },
    dark: { en: 'unset: `input`', zh: '不设：`input`' },
  },
  'outline-hover-foreground': {
    role: { en: 'Its words', zh: '它的文字' },
  },
  'outline-hover-edge-link': linked('outline-hover-edge', 'primary'),
  'outline-hover-foreground-link': linked(
    'outline-hover-foreground',
    'primary',
  ),
} as const satisfies Record<string, TokenDoc>;

/**
 * The primary as a fill under words, which a highlighted item links to, and
 * a selected row's mark that is not its colour (D76).
 */
export const MARK_DOCS = {
  'primary-fill': {
    role: {
      en: 'The primary as a filled area under words — the primary button, and a highlighted item linked to it; a step deeper where the primary is too light to carry white words (dark)',
      zh: '主色作为承载文字的填色——主按钮，以及链到它的高亮项；主色浅到托不住白字时（暗色）深一档',
    },
    dark: {
      en: "`primary`'s hue and chroma at a lightness of 0.52",
      zh: '`primary` 的色相与彩度，亮度 0.52',
    },
  },
  'primary-fill-foreground': {
    role: { en: 'The words on that fill', zh: '那块填色上的文字' },
    dark: { en: 'white', zh: '白' },
  },
  'row-selected-mark': {
    role: {
      en: "A bar down a selected row's left edge: the selection said by more than its colour",
      zh: '选中行左边的一道色条：不只靠颜色说出选中',
    },
    light: { en: '`primary`', zh: '`primary`' },
  },
  'row-selected-mark-link': linked('row-selected-mark', 'primary'),
} as const satisfies Record<string, TokenDoc>;
