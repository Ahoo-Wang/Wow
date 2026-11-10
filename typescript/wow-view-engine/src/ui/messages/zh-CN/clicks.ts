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

import type { clicksMessages } from '../clicks.js';

/**
 * `clicksMessages` 的简体中文，逐键对应（`../clicks.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const clicksZhCN = {
  // 点一个面板（批 D，D22 H、I）：追问菜单上的仪表盘筛选、交叉筛选、「点击时…」，
  // 以及内核与运行时关于它的发现。独立站在屏幕上的名字用「」。
  'label.drill.board': '仪表盘筛选：{conditions}',
  'label.drill.away': '（在工作台中打开）',
  'label.click.badge': '点击筛选「{filter}」',
  'label.click.badge-note':
    '点这个面板的一组，就用它设置仪表盘的「{filter}」；再点一次清除。',
  'label.click.from': '来自「{panel}」',
  'label.click.filtered': '「{filter}」已按点中的一组筛选',
  'label.click.filtered-to': '「{filter}」已按点中的一组筛选：{group}',
  'label.click.cleared': '「{filter}」不再按点中的一组筛选',
  'label.click.no-value': '这一组没有「{filter}」能用的值',
  'label.click.menu-item': '点击时…',
  'label.click.title': '点击「{panel}」时',
  'label.click.description':
    '读者点这个面板的一组（柱、扇区、表格的一行）时做什么。',
  'label.click.choice': '点一组',
  'label.click.menu': '打开追问菜单',
  'label.click.menu-hint':
    '查看这些记录、按其他维度细分、只显示这一组，都在工作台中打开。',
  'label.click.menu-no-route': '这个页面没有接工作台，点一组什么也不做。',
  'label.click.filter': '更新仪表盘筛选',
  'label.click.filter-hint':
    '其余接线的面板按点中的值筛选；这个面板保留所有组，只标出点中的那一组。再点一次清除。',
  'label.click.filter-none': '先把一个筛选接到这个面板按来分组的字段上。',
  'label.click.filter-pick': '筛选',
  'label.click.go': '去另一个视图、仪表盘或页面',
  'label.click.go-hint': '带上点中的值。',
  'label.click.go-kind': '去哪',
  'label.click.go-view': '视图',
  'label.click.go-url': '网址',
  'label.click.view-pick': '选择视图…',
  'label.click.view-change': '换一个…',
  'label.click.view-missing': '选一个点击后打开的视图。',
  'label.click.view-heading': '点「{panel}」去哪个视图',
  'label.click.url': '地址',
  'label.click.url-hint': '在要放点中值的地方写上其中一个：{fields}',
  'label.click.url-hint-none': '这个面板没有可以带过去的维度。',
  'label.click.url-invalid': '只能是 http、https、mailto 或本应用内的地址。',
  'label.click.go-board': '仪表盘',
  'label.click.board-pick': '选择仪表盘…',
  'label.click.board-change': '换一块…',
  'label.click.board-missing': '选一块点击后打开的仪表盘。',
  'label.click.board-heading': '点「{panel}」打开哪块仪表盘',
  'label.click.board-description': '接下来逐个列出它的筛选，由你说每个带什么。',
  'label.click.board-list-loading': '正在读取仪表盘…',
  'label.click.board-list-empty': '还没有已保存的仪表盘。',
  'label.click.board-list-none': '没有符合条件的仪表盘。',
  'label.click.board-values': '它的筛选',
  'label.click.board-values-hint':
    '每个筛选带点中那一组的一个维度的值、这个仪表盘上一个筛选此刻的值，或者不带；不带的从它的默认值开始。',
  'label.click.board-value': '这一组的{dimension}',
  'label.click.board-skip': '不带',
  'label.click.board-tab': '打开到',
  'label.click.board-tab-last': '读者上次看的那页',
  'label.click.board-no-source':
    '这个面板没有它能收的维度，这个仪表盘上也没有同类型的筛选。',
  'label.click.board-no-filters': '那块仪表盘没有筛选，点一组就原样打开它。',
  'label.click.board-loading': '正在读取它的筛选…',
  'label.click.board-unreadable': '那块仪表盘已被删除，或你没有权限打开它。',
  'label.click.board-stale':
    '带给{filters}的值已失效（那个筛选、这个面板的维度或这个仪表盘的筛选不在了），完成时会去掉。',
  'label.click.board-filter-value': '这个仪表盘的{filter}',
  'label.click.board-from-group': '点中的这一组',
  'label.click.board-from-board': '这个仪表盘的筛选',
  'label.click.board-stale-name': '「{filter}」',
  'label.click.save': '完成',
  'dashboard.click.invalid':
    '这个面板的「点击时」设置读不出来，点一组会打开追问菜单。',
  'dashboard.click.unpressable':
    '这个面板没有可点的组（记录视图，或展开了明细项的分析），「点击时」的设置不生效。',
  'dashboard.click.filter-unknown':
    '这个面板点击时要设置的筛选已经不在仪表盘上，点一组会打开追问菜单。',
  'dashboard.click.filter-unwired':
    '「{filter}」没有接到这个面板，点一组设置不了它，会打开追问菜单。',
  'dashboard.click.filter-ungrouped':
    '「{filter}」接的是 {field}，这个面板不按它分组，点一组拿不到它的值，会打开追问菜单。',
  'dashboard.click.url-unsafe':
    '点击时去的地址不是 http、https、mailto 或本应用内的地址，点一组会打开追问菜单。',
  'dashboard.click.url-unknown-field':
    '点击时去的地址用到了 {field}，这个面板不按它分组，点一组会打开追问菜单。',
  'dashboard.click.destination-unavailable':
    '点击时要去的视图已被删除，或你没有权限打开它。',
  'dashboard.click.destination-unsupported':
    '去视图时只能选记录视图或分析视图；要去仪表盘，选「仪表盘」。',
  'dashboard.click.view-unknown':
    '点击时要去的视图在这个应用里找不到，点一组会打开追问菜单。',
  'dashboard.click.view-undeclared':
    '「{definition}」里没有点击时要去的视图，点一组会打开追问菜单。',
  'dashboard.click.view-not-a-view':
    '点击时要去的不是记录视图或分析视图，点一组会打开追问菜单。',
  'dashboard.click.board-dimension-unknown':
    '点击要把 {field} 带到另一块仪表盘，这个面板已不按它分组，点一组会打开追问菜单。',
  'dashboard.click.board-gone':
    '点击时要打开的仪表盘已被删除，或你没有权限打开它，点一组会打开追问菜单。',
  'dashboard.click.board-not-a-board':
    '点击时要打开的不是一块仪表盘，点一组会打开追问菜单。',
  'dashboard.click.board-filter-unknown':
    '点击时要打开的仪表盘上已没有筛选「{filter}」，点一组会打开追问菜单。',
  'dashboard.click.board-filter-mismatch':
    '点击时要打开的仪表盘上的「{filter}」收不了 {field} 的值，点一组会打开追问菜单。',
  'dashboard.click.board-source-unknown':
    '点击要把这个仪表盘的筛选「{filter}」带到另一块仪表盘，但这个仪表盘上已没有它，点一组会打开追问菜单。',
} satisfies Record<keyof typeof clicksMessages, string>;
