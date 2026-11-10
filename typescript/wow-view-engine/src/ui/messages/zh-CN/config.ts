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

import type { configMessages } from '../config.js';

/**
 * `configMessages` 的简体中文，逐键对应（`../config.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const configZhCN = {
  // 每种视图都存的那部分配置。
  'config.refresh.missing': '这个视图没有刷新设置。',
  'config.refresh.not-an-integer': '刷新间隔必须是整秒。',
  'config.refresh.too-long': '刷新间隔不能超过 {max} 秒。',
  'config.refresh.too-short': '刷新间隔至少 {min} 秒。',
  'config.filter.invalid': '这个视图的条件无法读取。',
  'config.filterMode.unknown': '这个视图的筛选模式是未知的。',
  'config.filterMode.not-simple': '这些条件要用高级编辑器才能完整显示。',
  'config.invalid': '这个视图无法读取。',
} satisfies Record<keyof typeof configMessages, string>;
