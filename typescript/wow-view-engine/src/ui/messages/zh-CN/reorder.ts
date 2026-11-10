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

import type { reorderMessages } from '../reorder.js';

/**
 * `reorderMessages` 的简体中文，逐键对应（`../reorder.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const reorderZhCN = {
  // 每个能调顺序的列表共用的话：抓手怎么用，以及点一下抓手弹出的四个位置
  //（给拖不动的指针用的一次点击替代，WCAG 2.5.7）。
  'label.reorder.instructions':
    '按方向键移动一位；按空格键拿起，方向键移动，再按空格键放下，按 Esc 放回原处；也可以点一下抓手，选择移到哪里。',
  'label.reorder.first': '移到最前',
  'label.reorder.earlier': '往前移一位',
  'label.reorder.later': '往后移一位',
  'label.reorder.last': '移到最后',
} satisfies Record<keyof typeof reorderMessages, string>;
