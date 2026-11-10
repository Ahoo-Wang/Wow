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

import type { workbenchMessages } from '../workbench.js';

/**
 * `workbenchMessages` 的简体中文，逐键对应（`../workbench.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const workbenchZhCN = {
  // 工作台骨架：侧栏的两种状态、侧栏收起后顶替它的切换器，以及视图铺满整屏
  // 的两种状态。「铺满屏幕」而不是「全屏」：全屏是浏览器自己那个（F11），连
  // 地址栏一起收走，这里只是视图占满它所在的这一页。
  // 这份目录的语言，BCP 47 标签：面与它打开的弹层写作 `lang`，不显示。
  'label.language': 'zh-CN',
  'label.workbench.collapse-sidebar': '收起视图列表',
  'label.workbench.expand-sidebar': '展开视图列表',
  'label.workbench.expand-view': '铺满屏幕',
  'label.workbench.collapse-view': '退出铺满',
  'label.workbench.switch-view': '切换视图',
  // 背后没有视图时的同一个控件：它这时就是标签本身，看到的和读屏念到的是同
  // 一句；空按钮上写「切换视图」，等于给一个并不存在的视图提议离开。
  'label.workbench.choose-view': '选择视图',
  // 打开视图时那块骨架旁边的播报，只说一次：屏幕上的形状说的是页面将长成
  // 什么样，这一句说的是它的意思。
  'label.workbench.opening': '正在打开视图',
  'label.workbench.editor-modes': '编辑器选项',
} satisfies Record<keyof typeof workbenchMessages, string>;
