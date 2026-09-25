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
 * The README's words for the bounds a preset holds a brand colour to
 * (theme-architecture.md 2.2), beside `tokenDocs.ts` so that catalogue stays
 * one screenful of files: the brand group is nine entries of its own.
 *
 * A bound's default is read off the stylesheet's derivation; a bound with
 * none is unset, and what it bounds is not derived.
 */
const bound = (role: Words, unset = false): TokenDoc =>
  unset ? { role, light: { en: 'unset', zh: '不设' } } : { role };

export const BRAND_BOUND_DOCS = {
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
    en: "The lightness and chroma of the first chart slot under `data-fve-brand-chart` — the preset's own first slot's",
    zh: '有 `data-fve-brand-chart` 时图表第 1 色的亮度与彩度——即预设自己第 1 色的',
  }),
} as const satisfies Record<string, TokenDoc>;
