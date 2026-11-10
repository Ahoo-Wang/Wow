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

import type { renderMessages } from '../render.js';

/**
 * `renderMessages` 的简体中文，逐键对应（`../render.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const renderZhCN = {
  'label.render.failed': '这一块没能画出来',
  'label.render.failed-hint':
    '视图的其余部分照常可用；重新加载会重新绘制这一块。',
  'label.render.retry': '重新加载',
} satisfies Record<keyof typeof renderMessages, string>;
