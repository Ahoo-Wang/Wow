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

import type { scopeMessages } from '../scope.js';

/**
 * `scopeMessages` 的简体中文，逐键对应（`../scope.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const scopeZhCN = {
  // 视图的可见范围。
  'label.scope.only-me': '仅自己',
  'label.scope.everyone': '所有人',
  'label.scope.as-system': '所有人（系统视图）',
  'label.scope.group.personal': '我的视图',
  'label.scope.group.shared': '共享视图',
  'label.scope.group.system': '系统视图',
  'label.scope.system': '随定义提供，不能改名或删除',
  'label.scope.tag.system': '系统',
  'label.scope.tag.personal': '个人',
  'label.scope.tag.shared': '共享',
  'label.scope.personal.description': '只有你看得到。',
  'label.scope.shared.description': '用这份数据的人都看得到。',
  // 视图只存在这个浏览器里时（`ViewStore.local`），代替「给谁看」这一问。
  'label.scope.local': '保存在这个浏览器里：只有你看得到，也只在这里。',
  'label.scope.system.description':
    '用这份数据的人都看得到，列在系统视图里；只有获准的人能修改。',
  'label.scope.system-stored': '所有人都看得到；只有获准的人能修改',
  'label.scope.system-editable': '所有人都看得到；你可以修改，改动对所有人生效',
  'label.scope.no-permission': '（没有创建权限）',
  'label.scope.refused': '页面的作用域条件对这个视图不适用',
} satisfies Record<keyof typeof scopeMessages, string>;
