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

import type { filtersMessages } from '../filters.js';

/**
 * `filtersMessages` 的简体中文，逐键对应（`../filters.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const filtersZhCN = {
  // 搭仪表盘，批 B3（D22 C～E）：标签栏、在仪表盘里新建分析与另存为视图、面板自己的展示。
  'label.filters.bar': '筛选',
  'label.filters.required': '（必填）',
  'label.filters.clear': '清空',
  'label.filters.clear-one': '清除「{filter}」',
  'label.filters.back-to-default': '把「{filter}」恢复为默认值',
  'label.filters.idle': '这个标签页里没有受「{filter}」影响的面板。',
  'label.filters.grouping': '时间粒度',
  'label.filters.grouping-remove': '移除时间粒度',
  'label.filters.fixed': '固定范围',
  'label.filters.fixed-note':
    '仪表盘本身让每个面板都只显示这个范围，这里改不了。',
  'label.filters.fixed-remove': '移除固定范围',
  'label.filters.fixed-removed': '已移除固定范围',
  'label.filters.sheet-set': '筛选（已设 {count} 个）',
  'label.filters.not-reached': '不受{filters}影响',
  'label.filters.name-quoted': '「{name}」',
  'label.filters.add': '添加筛选',
  'label.filters.add-grouping': '时间粒度',
  'label.filters.type.date': '日期',
  'label.filters.type.text': '文本或类别',
  'label.filters.type.id': 'ID',
  'label.filters.type.number': '数字',
  'label.filters.type.boolean': '是否',
  'label.filters.type.search': '搜索',
  'label.filters.search-placeholder': '搜索…',
  'label.filters.removed': '已移除筛选「{filter}」',
  'label.filters.outcome': '已按{filters}筛选，{count} 个面板已更新。',
  'label.filters.outcome-one': '已按{filters}筛选，1 个面板已更新。',
  'label.filters.outcome-cleared': '已清除{filters}，{count} 个面板已更新。',
  'label.filters.outcome-cleared-one': '已清除{filters}，1 个面板已更新。',
  'label.filters.outcome-failed': '{count} 个面板没能加载。',
  'label.filters.outcome-failed-one': '1 个面板没能加载。',
  'label.filters.settings-of': '「{filter}」的设置',
  'label.filters.type': '类型',
  'label.filters.name': '名字',
  'label.filters.default': '默认值',
  'label.filters.multiple': '可多选',
  'label.filters.required-toggle': '必填',
  'label.filters.one-day-toggle': '只选一天',
  'label.filters.one-day-hint':
    '日历上的一天，或今天、昨天、前天；不能是一段时间。',
  'label.filters.date-own': '各面板自己的范围',
  'label.filters.required-hint': '永远有值：清空时回到默认值。',
  'label.filters.required-needs-default': '必填的筛选需要一个默认值。',
  'label.filters.source': '值从哪来',
  'label.filters.source.fields': '接上的字段',
  'label.filters.source.list': '自己列一组',
  'label.filters.source.fields-hint':
    '接上的字段自己列了值就从中选；没有就从数据里取候选值与记录数。',
  'label.filters.source.list-hint': '固定的几个值，在这里输入。',
  'label.filters.list': '这一组值',
  'label.filters.wire': '接线',
  'label.filters.remove': '移除筛选',
  'label.filters.wiring': '正在给「{filter}」接线',
  'label.filters.wiring-hint': '在每个面板上选它按哪个字段筛选。',
  'label.filters.wiring-done': '完成接线',
  'label.filters.wire-field': '筛选字段',
  'label.filters.wire-field-of': '「{panel}」按哪个字段受「{filter}」筛选',
  'label.filters.unwired': '不接',
  'label.filters.no-field': '没有可接的字段',
  'label.filters.manual': '手动',
  'label.filters.manual-hint': '亲手接的，不是按同名自动接的',
  'label.filters.auto-wired': '已自动接上 {count} 个有「{field}」字段的面板',
  'label.filters.auto-wired-one': '已自动接上 1 个有「{field}」字段的面板',
  'label.filters.auto-wired-search': '已自动接上 {count} 个有搜索框的面板',
  'label.filters.auto-wired-search-one': '已自动接上 1 个有搜索框的面板',
  'label.filters.only-picked': '只接刚选的面板',
  'label.filters.dismiss': '关闭',
  'label.filters.toasts': '接线提示',
  'label.filters.refused': '链接里有的筛选没能用上',
  'label.filters.reorder': '调整「{filter}」的顺序',
  'label.filters.picked': '已拿起筛选「{filter}」。',
  'label.filters.cancelled': '筛选「{filter}」留在原处。',
  'label.filters.moved': '「{filter}」现在是第 {index} 个筛选，共 {total} 个',
} satisfies Record<keyof typeof filtersMessages, string>;
