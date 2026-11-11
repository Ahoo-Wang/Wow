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

import { QueryErrorCodes } from '../../../model/index.js';
import { wireKey } from '../wireKey.js';
import type { viewMessages } from '../view.js';

/**
 * `viewMessages` 的简体中文，逐键对应（`../view.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const viewZhCN = {
  // 视图本身：哪一种、在哪个列表里，以及各条命令。
  'label.kind.record': '记录视图',
  'label.kind.analysis': '分析视图',
  'label.kind.dashboard': '仪表盘',
  'label.view.list': '视图',
  'label.view.list-failed': '列表加载失败。',
  'label.view.list-cut': '只列出前 {limit} 个视图；其余的凭链接仍能打开。',
  'label.view.none-hint': '新建一个视图，开始浏览数据。',
  'label.view.new': '新建视图',
  'label.view.new-title': '新视图',
  'label.view.unopenable': '无法打开这个视图',
  'label.view.open-default': '回到默认视图',
  'label.view.open-retry': '重新加载',
  'label.view.needs-fixing': '这个视图要先修正才能运行',
  'label.view.conditions-to-fix': '条件要先修正，视图才会查询（{count} 项）',
  'label.view.show-conditions': '查看条件',
  'label.view.unavailable':
    '这个视图用到了数据源现在不支持的功能，移除之前不会查询',
  'label.view.remove-unavailable': '移除不可用的条件',
  'label.view.warnings-count': '{count} 项值得留意',
  'label.view.warnings-count-one': '1 项值得留意',
  'label.view.none': '还没有视图',
  'label.origin.back': '返回 {title}',
  'label.origin.region': '从另一个视图打开',
  'label.origin.board-region': '从仪表盘打开',
  'label.drill.menu': '这一组',
  'label.drill.records': '查看这些记录',
  'label.drill.gap.nested-elements':
    '展开超过一层时，还不能按这一组找到记录：条件只能匹配第一层的元素。',
  'label.drill.split': '按其他维度细分…',
  'label.drill.focus': '只显示这一组',
  'label.drill.menu-span': '这段时间',
  'label.drill.split-span': '按其他维度细分这段时间…',
  'label.drill.focus-span': '只显示这段时间',
  'label.drill.set-filter': '设为「{filter}」',
  'label.drill.spanned': '已选中 {group}，追问菜单已打开。',
  'label.drill.span-hint': '按住 Shift 再选一行，追问两行之间的这段时间。',
  'label.drill.tap-again': '再点一下追问',
  'label.drill.press-hint': '点击追问',
  'label.drill.press-brush-hint': '点击追问 · 拖动框选一段',
  'label.drill.titled': '{subject} · {group}',
  'runtime.kind.not-declared': '{definition} 不提供 {kind} 视图。',
  'runtime.source.unresolved': '{source} 没有注册数据源。',
  'runtime.options.unresolved': '{source} 没有配置候选来源。',
  'runtime.query.failed': '没能加载数据：{reason}',
  'runtime.query.unreachable': '没能加载数据：无法连接服务端。',
  'runtime.query.forbidden': '无权限查看这些数据，请联系管理员开通。',
  [wireKey('runtime.query.failed', QueryErrorCodes.INVALID_JSON)]:
    '服务端读不懂这个视图发出的查询：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.BODY_NOT_OBJECT)]:
    '服务端读不懂这个视图发出的查询：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.EMPTY_BODY)]:
    '服务端收到的是空查询：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.UNKNOWN_PROPERTY)]:
    '服务端不认识这条查询的一部分，可能版本比视图引擎旧：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.UNKNOWN_TYPE)]:
    '服务端不认识这个视图用到的运算或指标，可能版本比视图引擎旧：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.UNKNOWN_VALUE)]:
    '服务端不认识这个视图发出的某个取值：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.INVALID_VALUE)]:
    '查询里有个值缺了或类型不对：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.INVALID_REQUEST)]:
    '服务端拒绝了这条查询：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.UNKNOWN_FIELD)]:
    '服务端不认识字段「{field}」。视图可能比数据新；去掉用到它的条件、列或维度。',
  [wireKey('runtime.query.failed', QueryErrorCodes.UNSUPPORTED_CAPABILITY)]:
    '「{field}」在这份数据上不能这样用：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.ELEMENT_SCOPE_REQUIRED)]:
    '「{field}」只能在它所属的列表里，按列表元素的条件筛选。',
  [wireKey('runtime.query.failed', QueryErrorCodes.VALUE_MISMATCH)]:
    '有个值不是「{field}」能取的值。',
  [wireKey('runtime.query.failed', QueryErrorCodes.NOT_COLLECTION)]:
    '「{field}」不是列表，不能判断是否为空。',
  [wireKey('runtime.query.failed', QueryErrorCodes.NOT_SINGLE_STRING)]:
    '「{field}」不是单个文本，不能判断是否为空。',
  [wireKey('runtime.query.failed', QueryErrorCodes.MODEL_SEARCH_UNSUPPORTED)]:
    '这份数据不支持搜索。',
  [wireKey('runtime.query.failed', QueryErrorCodes.CURSOR_NOT_ALLOWED)]:
    '「{field}」不能用来翻页。',
  [wireKey('runtime.query.failed', QueryErrorCodes.PROTECTED_AGGREGATION)]:
    '「{field}」受保护，不能用来汇总。',
  [wireKey('runtime.query.failed', QueryErrorCodes.PROTECTED_COMPARISON)]:
    '「{field}」受保护，不能用来筛选、排序或搜索。',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.MISSING_KEY_REQUIRES_STRING,
  )]: '只有单个文本字段才能把缺值归为一组，「{field}」不是。',
  [wireKey('runtime.query.failed', QueryErrorCodes.ANY_REQUIRES_SINGLE_VALUE)]:
    '「任一值」只能用于单值字段。',
  [wireKey('runtime.query.failed', QueryErrorCodes.INCOMPLETE_PROJECTION)]:
    '服务端这里给不出完整记录，请选择要显示的列。',
  [wireKey('runtime.query.failed', QueryErrorCodes.METRIC_FILTER_SEARCH)]:
    '指标自己的条件不能用搜索。',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.METRIC_FILTER_ELEMENT_MATCH,
  )]: '指标自己的条件不能按列表元素匹配。',
  [wireKey('runtime.query.failed', QueryErrorCodes.METRIC_FILTER_ARRAY_FIELD)]:
    '指标自己的条件不能用列表字段「{field}」。',
  [wireKey('runtime.query.failed', QueryErrorCodes.CURSOR_SORT_DUPLICATE)]:
    '排序里「{field}」出现了两次，保留一次即可。',
  [wireKey('runtime.query.failed', QueryErrorCodes.CURSOR_SORT_TOO_MANY)]:
    '排序字段太多，没法在这份数据上翻页；去掉几个。',
  [wireKey('runtime.query.failed', QueryErrorCodes.NOT_PROJECTABLE)]:
    '「{field}」不能作为这份数据的列显示。',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.EVENT_PROJECTION_TYPE_REQUIRED,
  )]: '事件内容不能脱离它的类型显示；保留事件类型这一列。',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.TEMPORAL_REPRESENTATION_REQUIRED,
  )]: '「{field}」存的不是已知的日期或时间，不能按相对时间筛选。',
  [wireKey('runtime.query.failed', QueryErrorCodes.PARALLEL_ARRAY_SORT)]:
    '「{field}」与排序里的另一个列表字段不能同时排序，只保留其中一个。',
  [wireKey('runtime.query.failed', QueryErrorCodes.ARRAY_EQUALITY)]:
    '这里不能拿「{field}」和整个列表比较，请改为匹配其中的元素。',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.FIRST_LAST_REQUIRES_SINGLE_VALUE,
  )]: '「首个值」「最后一个值」只能用于单值字段，「{field}」可能有多个值。',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.FIRST_LAST_REQUIRES_ORDER_BY,
  )]:
    '「{field}」的「首个值」「最后一个值」在这里要指定排序字段：这个位置没有事件时间可排。',
  [wireKey('runtime.query.failed', QueryErrorCodes.INVALID_CURSOR)]:
    '这一页没法接着往下翻了，请从第一页重新开始。',
  [wireKey('runtime.query.failed', QueryErrorCodes.STREAM_NOT_RESUMABLE)]:
    '按这个顺序没法一直读到底；换一种排序，或者少读一些记录。',
  [wireKey('runtime.query.failed', QueryErrorCodes.SIZE_OUT_OF_RANGE)]:
    '服务端限制了一次能读多少行：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.FILTER_TOO_LARGE)]:
    '条件超出了服务端一次查询能接受的数量：{reason}',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.EXPENSIVE_OPERATOR_DISABLED,
  )]: '服务端在这里不允许这种高代价的查询：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.COUNT_REQUIRES_FILTER)]:
    '先添加一个条件：服务端不会统计全部记录。',
  [wireKey('runtime.query.failed', QueryErrorCodes.RESIDUAL_GROUPS_EXCEEDED)]:
    '这份汇总的分组太多，服务端没法按指标筛选或排序；先缩小数据范围。',
  [wireKey('runtime.query.failed', QueryErrorCodes.TOO_MANY_GROUPS)]:
    '这份汇总的分组超出了能显示的数量，又没有排序来决定显示哪些；请设置排序，或调大显示数量。',
  [wireKey('runtime.query.failed', QueryErrorCodes.EXPLICIT_ENTRY_REQUIRED)]:
    '查询没有指明要在哪个入口上运行：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.REPLAY_TOO_LONG)]:
    '要看的状态离现在的变更太多，无法重建：{reason}',
  [wireKey(
    'runtime.query.failed',
    QueryErrorCodes.TEMPORAL_AGGREGATION_UNSUPPORTED,
  )]: '「{field}」没有按日期或时间戳存时间，不能按时间分组或计算时间差。',
  [wireKey('runtime.query.failed', QueryErrorCodes.SORT_TOO_MANY)]:
    '排序字段太多，去掉几个。',
  [wireKey('runtime.query.failed', QueryErrorCodes.SORT_FIELD_DUPLICATE)]:
    '「{field}」排了两次，保留一次即可。',
  [wireKey('runtime.query.failed', QueryErrorCodes.IDENTITY_UNDEFINED)]:
    '这份数据没有定义记录标识，不能逐页翻阅或按标识选取。',
  [wireKey('runtime.query.failed', QueryErrorCodes.STORAGE_UNSUPPORTED)]:
    '服务端的存储运行不了这条查询：{reason}',
  [wireKey('runtime.query.failed', QueryErrorCodes.PREVIOUS_UNSHIFTABLE)]:
    '与上一期对比要把「{field}」的时间范围前移，但筛选里对它的条件没法前移；把它的条件放在最外层的「且」里。',
  'record.detail.failed': '读不到完整记录：{reason}',
  'runtime.query.queue-full': '同时查询太多，过一会儿再试。',
  'runtime.query.too-many-nodes':
    '这些条件组成的查询有 {count} 个节点，数据源最多收 {max} 个。请删掉一些条件。',
  'runtime.query.too-many-values':
    '有一个条件列了 {count} 个值，数据源一个条件最多收 {max} 个。',
  'runtime.summary.page-only': '合计查询失败，表尾只汇总了本页的行。',
  'runtime.kanban.counts-unavailable':
    '各栏的条数没取到，栏头只写已载入的张数。',
  'view.abandon.failed': '无法放弃那次写入。',
  'view.change.notify-failed':
    '视图变化的订阅者出错：{reason}。列表可能慢一拍。',
  'view.config.invalid': '先修正这个视图报出的问题，再保存。',
  'view.create.forbidden': '你不能在这里创建视图。',
  'view.definition.invalid': '{id} 定义有 {issues} 个问题，无法打开。',
  'view.definition.not-found': '没有名为 {id} 的定义。',
  'view.changeAudience.failed': '没能改变这个视图的可见范围。',
  'view.addToDashboard.failed': '没能放进仪表盘。',
  'view.addToDashboard.not-board': '那不是一个仪表盘。',
  'view.addToDashboard.unsaved':
    '这个仪表盘在本页打开着，还有没保存的修改：先保存或还原。',
  'view.addToDashboard.full': '这个仪表盘已经有 {max} 块面板了。',
  'view.changeAudience.forbidden':
    '你不能改变这个视图的可见范围，另存一份自己的吧。',
  'view.changeAudience.unsupported':
    '当前的视图存储不支持在个人与共享之间移动视图。',
  'view.changeAudience.invalid': '没能改变这个视图的可见范围：{reason}',
  'view.changeAudience.invalid.shared-boards':
    '这个视图仍是共享的：共享仪表盘显示着它（{boards}）。',
  'view.changeAudience.invalid.shared-boards-one':
    '这个视图仍是共享的：一个共享仪表盘显示着它（{boards}）。',
  'view.delete.failed': '这个视图删不掉。',
  'view.delete.forbidden': '你不能删除这个视图，请联系它的归属人。',
  'view.list.failed': '视图列表加载失败。',
  'view.list.reserved-id': '存储的视图 {id} 用了保留 id，已跳过。',
  'view.open.failed': '无法打开这个视图。',
  'view.open.not-found': '没有名为 {id} 的视图。',
  'view.open.wrong-kind': '这个视图是另一种类型（{kind}），这个页面无法显示。',
  'view.open.other-definition': '这个视图属于另一个页面，这个页面无法显示。',
  'view.preferences.default-forbidden': '你不能设置默认视图。',
  'view.preferences.failed': '视图偏好保存失败。',
  'view.preferences.load-failed': '视图偏好加载失败，视图按服务端顺序显示。',
  'view.preferences.reorder-forbidden': '你不能给视图排序。',
  'view.rename.failed': '视图改名失败。',
  'view.rename.forbidden': '你不能给这个视图改名，另存一份自己的吧。',
  'view.resolve.failed': '冲突未能解决。',
  'view.retry.failed': '重试失败。',
  'view.runtime.not-owned': '这个视图在这里已经不是打开着的了。',
  'view.save-as.failed': '这个视图存不成副本。',
  'view.publish.failed': '这个视图没能发布为系统视图。',
  'view.publish.forbidden': '你没有发布系统视图的权限，请联系管理员。',
  'view.save.failed': '视图保存失败。',
  'view.save.forbidden': '你不能保存对这个视图的改动，另存一份自己的吧。',
  'view.system.read-only': '系统视图不能改（{action}）。',
  'view.title.empty': '视图需要一个标题。',
  'view.title.too-long': '视图标题最多 {max} 个字。',
  'view.config.too-large': '这个视图太大，存不下：{size} KB，上限是 {max} KB。',
  'view.field.deprecated': '这个视图用到了「{field}」，数据源已弃用它。',
  'view.field.deprecated-because':
    '这个视图用到了「{field}」，数据源已弃用它：{reason}',
  'view.write.conflict-unreadable': '别人先保存了，且最新版本无法读取。',
  'view.write.not-a-conflict': '那次写入是 {kind}，不是冲突。',
  'view.write.not-pending': '那次写入已经结清了。',
  'view.write.in-flight': '这个视图正在保存，等它结束。',
  'view.write.unknown-pending':
    '上一次 {action} 还没有确认。先重试或搁置它，再写下一次。',
  'view.write.conflict': '别人先保存了这个视图。',
  'view.write.forbidden': '你不能写这个视图。',
  'view.write.invalid': '服务端拒绝了这次写入：{reason}',
  'view.write.not_found': '这个视图已不存在。',
  'view.write.unavailable': '无法连接服务端。',
  'view.write.unavailable.server': '服务端暂时无法处理这次写入：{reason}',
  'view.write.unsupported': '服务端未提供视图存储。',
  'view.write.storage': '这个浏览器没能把这次修改存下来（存储已满或被禁用）。',
  'record.detail.forbidden': '你没有权限查看这条记录。',
  'view.open.failed.not_found': '这个视图已不存在。',
  'view.open.failed.forbidden': '你没有这个视图的权限，请联系管理员开通。',
  'view.open.failed.unavailable': '无法加载这个视图：无法连接服务端。',
  'view.list.failed.unavailable': '视图列表加载失败：无法连接服务端。',
  'view.open.failed.unavailable.server':
    '无法加载这个视图：服务端暂时无法处理（{reason}）。',
  'view.list.failed.unavailable.server':
    '视图列表加载失败：服务端暂时无法处理（{reason}）。',
  'view.open.failed.unsupported': '无法加载这个视图：服务端未提供视图存储。',
  'view.list.failed.unsupported': '视图列表加载失败：服务端未提供视图存储。',
} satisfies Record<keyof typeof viewMessages, string>;
