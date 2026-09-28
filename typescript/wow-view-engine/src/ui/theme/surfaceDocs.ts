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

import type { TokenDoc } from './tokenDocs.js';

/**
 * The README's words for the roles of the grounds and the cards on them —
 * the grouped ground, the rows' ground, a card's ring and lift, whether a
 * record's result is a card (D69), the scrim (theme-architecture.md 4.2) —
 * beside `tokenDocs.ts` so that catalogue stays one screenful of files.
 */
export const SURFACE_DOCS = {
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
  'result-card': {
    role: {
      en: "`1`: a record workbench's result is a card on `canvas`, in `card-edge`, `card-shadow` and `radius-card`, as a board's panel is",
      zh: '`1`：记录工作台的结果是 `canvas` 上的一张卡片，与看板面板同一套 `card-edge`、`card-shadow`、`radius-card`',
    },
    light: {
      en: "unset: a band to the work column's edges, one rule on top",
      zh: '不设：贴边的带',
    },
  },
  scrim: {
    role: {
      en: 'What dims the page behind a dialog or a sheet',
      zh: '对话框与抽屉背后压暗页面的遮罩',
    },
  },
} satisfies Record<string, TokenDoc>;
