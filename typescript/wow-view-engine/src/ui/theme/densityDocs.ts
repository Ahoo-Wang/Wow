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
 * The README's words for the density's lengths (themes.md 2.4,
 * theme-architecture.md 7), beside `tokenDocs.ts` so that catalogue stays
 * one screenful of files. Each is a host variable of the layout whose
 * default the density step gives, so the default is said as the three
 * steps draw it — compact, default, comfortable — rather than read off
 * the `calc()` behind it.
 */
const densityLength = (
  role: Words,
  [compact, middle, comfortable]: readonly [number, number, number],
): TokenDoc => ({
  role,
  light: {
    en: `by the density: ${compact} / ${middle} / ${comfortable}px`,
    zh: `随密度：${compact} / ${middle} / ${comfortable}px`,
  },
});

export const DENSITY_LENGTH_DOCS = {
  'table-header-height': densityLength(
    {
      en: "A table's header row, over the density",
      zh: '表格表头行的高度，压过密度',
    },
    [32, 40, 44],
  ),
  'table-cell-padding-block': densityLength(
    {
      en: "The space above and below a table cell's value, over the density",
      zh: '表格单元格里值上下的留白，压过密度',
    },
    [4, 8, 10],
  ),
  'table-cell-padding-inline': densityLength(
    {
      en: "The space either side of a table cell's value, over the density",
      zh: '表格单元格里值左右的留白，压过密度',
    },
    [6, 8, 12],
  ),
  'sidebar-item-height': densityLength(
    {
      en: 'A view in the view list, over the density; keep it 24px or more (WCAG 2.5.8)',
      zh: '视图列表里一项的高度，压过密度；不要低于 24px（WCAG 2.5.8）',
    },
    [24, 28, 32],
  ),
  'panel-padding': densityLength(
    {
      en: "The space round a dashboard panel's content, over the density; above and below it stops at 12px (the board's 80px row)",
      zh: '仪表盘面板内容四周的留白，压过密度；上下最多 12px（仪表盘的 80px 行高）',
    },
    [8, 12, 16],
  ),
} as const satisfies Record<string, TokenDoc>;
