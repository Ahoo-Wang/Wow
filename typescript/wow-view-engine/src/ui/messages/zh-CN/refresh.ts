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

import type { refreshMessages } from '../refresh.js';

/**
 * `refreshMessages` 的简体中文，逐键对应（`../refresh.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const refreshZhCN = {
  // 自动刷新：刷新按钮 ▾ 里的间隔菜单，以及开启时按钮上的那处凭据。
  'label.refresh.auto': '自动刷新',
  'label.refresh.off': '关闭',
  'label.refresh.seconds': '{count} 秒',
  'label.refresh.minutes': '{count} 分钟',
  'label.refresh.hours': '{count} 小时',
  'label.refresh.on': '这个视图每 {interval}自动刷新一次。',
  'label.refresh.panels':
    '仪表盘上的所有面板按这里的间隔一起刷新；面板所显示的视图自己保存的刷新间隔在这里不生效。',
  'label.refresh.read-at': '更新于 {time}',
} satisfies Record<keyof typeof refreshMessages, string>;
