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

import type { buildingMessages } from '../building.js';

/**
 * `buildingMessages` 的简体中文，逐键对应（`../building.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const buildingZhCN = {
  'label.tabs.name': '标签页',
  'label.tabs.add': '添加标签页',
  'label.tabs.new-title': '标签页 {index}',
  'label.tabs.first-title': '总览',
  'label.tabs.actions': '标签页「{title}」',
  'label.tabs.rename': '改名',
  'label.tabs.rename-field': '标签页「{title}」的名字',
  'label.tabs.remove': '删除标签页',
  'label.tabs.reorder': '调整「{title}」的顺序',
  'label.tabs.picked': '已拿起标签页「{title}」。',
  'label.tabs.cancelled': '标签页「{title}」留在原处。',
  'label.tabs.moved': '「{title}」现在是第 {index} 个标签页，共 {total} 个',
  'label.tabs.removed': '已删除标签页「{title}」',
  'label.tabs.remove-heading': '删除标签页「{title}」？',
  'label.tabs.remove-description':
    '它上面的 {count} 个面板会一起删除。按「保存」之前不会存下，按「撤销」可以找回。',
  'label.tabs.remove-description-one':
    '它上面的 1 个面板会一起删除。按「保存」之前不会存下，按「撤销」可以找回。',
  'label.tabs.remove-confirm': '删除标签页和面板',
  'label.tabs.empty': '这个标签页还没有面板',
  'label.tabs.empty-hint':
    '停在这个标签页上添加视图或分析，或者从别的标签页把面板移过来。',
  'label.panel.moved-to-tab': '「{title}」已移到标签页「{tab}」',
  'label.panel.new-analysis.heading': '新建分析',
  'label.panel.new-analysis.heading-of': '新建分析 · {definition}',
  'label.panel.new-analysis.description':
    '它只属于这个仪表盘：随仪表盘保存、共享和删除，不出现在视图列表里。',
  'label.panel.new-analysis.data': '数据',
  'label.panel.new-analysis.pick': '选择要分析的数据',
  'label.panel.new-analysis.pick-hint': '这里只列出能做分析的数据。',
  'label.panel.new-analysis.none': '这里没有能做分析的数据',
  'label.panel.new-analysis.failed': '这份数据打不开：{reason}',
  'label.panel.new-analysis.close-visualization': '收起可视化',
  'label.panel.new-analysis.title': '标题',
  'label.panel.new-analysis.title-hint': '起名之前，按它显示的内容命名。',
  'label.panel.new-analysis.add': '放进仪表盘',
  'label.panel.new-analysis.blocked': '先按上面的提示修正分析，再放进仪表盘。',
  'label.panel.new-analysis.added': '「{title}」已放进仪表盘',
  'label.panel.new-analysis.full': '这个仪表盘放不下更多面板了。',
  'label.panel.save-owned.heading': '另存为视图',
  'label.panel.save-owned.description':
    '它会成为「{definition}」的一个视图，可以在工作台里打开；这块面板从此显示那个视图。',
  'label.panel.save-owned.submit': '保存视图',
  'label.panel.save-owned.saved': '已另存为视图「{title}」，这块面板改为显示它',
  'label.panel.copy-shared.heading': '复制为共享视图并替换',
  'label.panel.copy-shared.description':
    '「{view}」是个人视图，这块共享仪表盘的其他读者在这个面板里什么也看不到。它会复制成「{definition}」的一个共享视图并立即保存，这块面板改为显示副本，样子与筛选都和现在一样；你的个人视图保持不变。按「完成」保存仪表盘。',
  'label.panel.copy-shared.submit': '复制并替换',
  'label.panel.copy-system.heading': '复制为系统视图并替换',
  'label.panel.copy-system.description':
    '「{view}」不是系统视图，其他租户的读者在这块系统仪表盘的这个面板里什么也看不到。它会复制成「{definition}」的一个系统视图并立即保存，这块面板改为显示副本，样子与筛选都和现在一样；原来的视图保持不变。按「完成」保存仪表盘。',
  'label.panel.copy-system.saved':
    '已复制为系统视图「{title}」，这块面板改为显示它',
  'label.panel.copy-shared.saved':
    '已复制为共享视图「{title}」，这块面板改为显示它',
  'label.panel.presentation.reset': '恢复为视图的样子',
  'label.panel.presentation.heading': '「{title}」在这里的展示',
  'label.panel.presentation.description':
    '只改这块面板的样子。它显示的视图保持自己的样子，问的问题也不变。',
  'label.panel.presentation.done': '完成',
  'label.panel.presentation.preview': '这块面板改后的样子',
  'label.panel.presentation.nothing':
    '这块面板还没有结果可画，等它有了结果再来改。',
  'label.panel.presentation.as': '此处改为{type}',
  'label.panel.presentation.changed': '此处改了展示',
  'label.panel.presentation.note':
    '这块面板和它显示的视图长得不一样是有意的：只在这个仪表盘里改过。',
} satisfies Record<keyof typeof buildingMessages, string>;
