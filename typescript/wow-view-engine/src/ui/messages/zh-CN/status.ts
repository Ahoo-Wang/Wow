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

import type { statusMessages } from '../status.js';

/**
 * `statusMessages` 的简体中文，逐键对应（`../status.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const statusZhCN = {
  // 视图自己要说的话：哪里失败了，屏幕上留着的又是什么，此刻有没有东西在跑。
  'label.status.show': '展开 {count} 项',
  'label.status.less': '收起',
  'label.status.loading': '加载中',
  'label.status.querying': '正在查询',
  'label.status.then': '{first}；{then}',
  'label.status.groups': '{count} 组',
  'label.status.groups-one': '1 组',
  // 只有一条 error 时直接说出来，行尾给一条出路：记录视图自己的错几乎都是
  // 列的事（定义里没有的列、钉不了的列），修它的地方就是工具栏那颗按钮打开
  // 的那一块。说的是打开哪一块，不是替读者决定怎么改。
  'label.status.open-columns': '打开列设置',
  'label.status.open-sort': '打开排序设置',
  'label.status.open-card': '打开卡片设置',
  'label.status.use-page-size': '改为每页 {size} 条',
  'label.status.use-layout': '改用{layout}',
  'label.query.failed': '查询失败',
  'label.query.forbidden': '无权限',
  'label.query.stale': '{error} · 显示的是上一次成功的结果',
  'label.query.stale-conditions': '{error} · 显示的是改条件之前的结果',
  'label.query.retry': '重新加载',
  'label.write.conflict': '别人先保存了这个视图',
  'label.write.unknown': '{what}没有收到答复，可能已经成功',
  'label.write.action.save': '保存',
  'label.write.action.create': '另存',
  'label.write.action.rename': '重命名',
  'label.write.action.changeAudience': '更改可见范围',
  'label.write.action.delete': '删除',
  'label.write.action.preferences': '保存个人偏好',
  'label.image.failed': '图片加载失败',
  'label.image.link': '打开链接的页面',
} satisfies Record<keyof typeof statusMessages, string>;
