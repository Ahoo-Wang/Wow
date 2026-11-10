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

import type { dashboardMessages } from '../dashboard.js';

/**
 * `dashboardMessages` 的简体中文，逐键对应（`../dashboard.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const dashboardZhCN = {
  // 仪表盘网格、面板，以及它们背后的内核。
  'label.dashboard.needs-fixing': '这个仪表盘要先修正才能运行',
  'label.dashboard.panels-need-fixing': '有 {count} 个面板要先修正才能显示',
  'label.dashboard.panels-need-fixing-one': '有 1 个面板要先修正才能显示',
  'label.dashboard.new': '新建仪表盘',
  'label.dashboard.new-title': '新仪表盘',
  'label.dashboard.none': '还没有仪表盘',
  'label.dashboard.more': '更多仪表盘操作',
  'label.dashboard.leave-heading': '离开这个仪表盘？',
  'label.dashboard.refresh-on': '这个仪表盘每 {interval}自动刷新一次。',
  'label.dashboard.list': '仪表盘',
  'label.dashboard.manage-group': '仪表盘「{title}」的操作',
  'label.dashboard.publish': '发布为系统仪表盘',
  'label.dashboard.publish-failed': '这个仪表盘没能发布为系统仪表盘。',
  'label.dashboard.publish-forbidden':
    '你没有发布系统仪表盘的权限，请联系管理员。',
  'label.dashboard.delete-system-consequence':
    '删除后，所有人都不再看到这个系统仪表盘。',
  'label.dashboard.collapse-sidebar': '收起仪表盘列表',
  'label.dashboard.expand-sidebar': '展开仪表盘列表',
  'label.dashboard.switch': '切换仪表盘',
  'label.dashboard.choose': '选择仪表盘',
  'label.dashboard.opening': '正在打开仪表盘',
  'label.dashboard.write-conflict': '别人先保存了这个仪表盘',
  'label.dashboard.scope-refused': '页面的作用域条件对这个仪表盘不适用',
  'label.dashboard.render-hint':
    '仪表盘的其余部分照常可用；重新加载会重新绘制这一块。',
  'label.dashboard.unopenable': '无法打开这个仪表盘',
  'label.dashboard.open-default': '回到默认仪表盘',
  'label.dashboard.save-group': '仪表盘操作',
  'label.dashboard.saved-announce': '仪表盘已保存',
  'label.dashboard.shared-heading': '更新所有人看到的仪表盘？',
  'label.dashboard.first-heading': '保存仪表盘',
  'label.dashboard.save-as.heading': '另存为新仪表盘',
  'label.dashboard.save-as.description': '正在看的这个仪表盘保持原样。',
  'label.dashboard.save-as.submit': '创建仪表盘',
  'label.dashboard.delete-consequence':
    '只删除仪表盘：记录和面板显示的已保存视图仍然保留，在仪表盘里新建的分析随之删除。',
  'label.dashboard.group.personal': '我的仪表盘',
  'label.dashboard.group.shared': '共享仪表盘',
  'label.dashboard.group.system': '系统仪表盘',
  'label.dashboard.manage': '管理仪表盘',
  'label.dashboard.manage-description':
    '改名、排序、删除仪表盘，并选择默认打开的仪表盘。',
  'label.dashboard.config-invalid': '先修正这个仪表盘报出的问题，再保存。',
  'label.dashboard.create-forbidden': '你不能在这里创建仪表盘。',
  'label.dashboard.audience-failed': '没能改变这个仪表盘的可见范围。',
  'label.dashboard.audience-forbidden':
    '你不能改变这个仪表盘的可见范围，另存一份自己的吧。',
  'label.dashboard.audience-unsupported':
    '当前的存储不支持在个人与共享之间移动仪表盘。',
  'label.dashboard.audience-invalid': '没能改变这个仪表盘的可见范围：{reason}',
  'label.dashboard.audience-shared-boards':
    '这个仪表盘仍是共享的：共享仪表盘显示着它（{boards}）。',
  'label.dashboard.audience-shared-boards-one':
    '这个仪表盘仍是共享的：一个共享仪表盘显示着它（{boards}）。',
  'label.dashboard.delete-failed': '这个仪表盘删不掉。',
  'label.dashboard.delete-forbidden':
    '你不能删除这个仪表盘，请联系它的归属人。',
  'label.dashboard.list-failed': '仪表盘列表加载失败。',
  'label.dashboard.list-unavailable': '仪表盘列表加载失败：无法连接服务端。',
  'label.dashboard.list-unavailable-server':
    '仪表盘列表加载失败：服务端暂时无法处理（{reason}）。',
  'label.dashboard.list-unsupported':
    '仪表盘列表加载失败：服务端不保存仪表盘。',
  'label.dashboard.reserved-id': '存储的仪表盘 {id} 用了保留 id，已跳过。',
  'label.dashboard.notify-failed':
    '仪表盘变化的订阅者出错：{reason}。列表可能慢一拍。',
  'label.dashboard.open-failed': '无法打开这个仪表盘。',
  'label.dashboard.not-found': '没有名为 {id} 的仪表盘。',
  'label.dashboard.wrong-kind': '这不是仪表盘（{kind}），这个页面无法显示。',
  'label.dashboard.other-definition':
    '这个仪表盘属于另一个页面，这个页面无法显示。',
  'label.dashboard.gone': '这个仪表盘已不存在。',
  'label.dashboard.open-forbidden':
    '你没有这个仪表盘的权限，请联系管理员开通。',
  'label.dashboard.open-unavailable': '无法加载这个仪表盘：无法连接服务端。',
  'label.dashboard.open-unavailable-server':
    '无法加载这个仪表盘：服务端暂时无法处理（{reason}）。',
  'label.dashboard.open-unsupported':
    '无法加载这个仪表盘：服务端不保存仪表盘。',
  'label.dashboard.write-unsupported': '服务端不保存仪表盘。',
  'label.dashboard.default-forbidden': '你不能设置默认仪表盘。',
  'label.dashboard.preferences-failed': '仪表盘偏好保存失败。',
  'label.dashboard.preferences-load-failed':
    '仪表盘偏好加载失败，仪表盘按服务端顺序显示。',
  'label.dashboard.reorder-forbidden': '你不能给仪表盘排序。',
  'label.dashboard.rename-failed': '仪表盘改名失败。',
  'label.dashboard.rename-forbidden':
    '你不能给这个仪表盘改名，另存一份自己的吧。',
  'label.dashboard.not-open': '这个仪表盘在这里已经不是打开着的了。',
  'label.dashboard.save-as-failed': '这个仪表盘存不成副本。',
  'label.dashboard.save-failed': '仪表盘保存失败。',
  'label.dashboard.save-forbidden':
    '你不能保存对这个仪表盘的改动，另存一份自己的吧。',
  'label.dashboard.system-read-only': '系统仪表盘不能改（{action}）。',
  'label.dashboard.title-empty': '仪表盘需要一个标题。',
  'label.dashboard.title-too-long': '仪表盘标题最多 {max} 个字。',
  'label.dashboard.config-too-large':
    '这个仪表盘太大，存不下：{size} KB，上限是 {max} KB。',
  'label.dashboard.write-in-flight': '这个仪表盘正在保存，等它结束。',
  'label.dashboard.write-forbidden': '你不能写这个仪表盘。',
  'label.dashboard.unreadable': '这个仪表盘无法读取。',
  'label.dashboard.refresh-missing': '这个仪表盘没有刷新设置。',
  'label.dashboard.not-declared': '{definition} 不提供仪表盘。',
  'label.dashboard.empty': '这个仪表盘还没有面板',
  'label.dashboard.empty-hint':
    '仪表盘把已保存的记录视图和分析视图并排放在一起看。',
  'label.panel.untitled': '面板 {index}',
  'label.panel.numbered': '{name} {n}',
  'label.panel.kind.heading': '标题',
  'label.panel.kind.markdown': '文字',
  'label.panel.kind.image': '图片',
  'label.panel.kind.links': '链接',
  'label.panel.out.missing': '这个面板显示的视图已被删除，或者你没有查看权限',
  'label.panel.out.failed': '这个面板显示的视图没能打开',
  'label.panel.out.kind': '这个面板指向的不是记录视图或分析视图',
  'label.panel.out.private': '这个面板显示的视图并不对这个仪表盘的所有读者开放',
  'label.panel.out.filter': '仪表盘的筛选接不到这个面板上',
  'label.panel.out.config': '这个面板用不了',
  'label.panel.out.blocked': '仪表盘本身要先修正',
  'label.panel.out.unknown-kind': '这种面板在这里显示不了',
  'label.panel.out.settings': '这个面板自己的设置用不了',
  'label.panel.way-out.maintainer': '请这个仪表盘的维护者替换或移除这个面板。',
  'label.panel.way-out.share':
    '请视图的所有者共享给你，或者请这个仪表盘的维护者替换或移除这个面板。',
  'label.panel.way-out.widen':
    '请视图的所有者把它共享到和这个仪表盘一样的范围，或者请这个仪表盘的维护者替换或移除这个面板。',
  'label.panel.way-out.author':
    '请视图的所有者打开它修正，或者请这个仪表盘的维护者替换这个面板。',
  'label.panel.way-out.dashboard': '仪表盘修正之后，这个面板会重新运行。',
  'label.panel.way-out.retry':
    '请重新加载；一直打不开的话，请联系这个仪表盘的维护者。',
  'label.panel.retry': '重新加载',
  'label.panel.finding': '「{panel}」：{finding}',
  'label.panel.resize': '调整「{title}」的大小',
  'label.panel.handle': '移动或调整「{title}」大小',
  'label.panel.handle-hint':
    '拖动它，或按 Enter 用键盘摆放：方向键移动，Shift＋方向键调整大小，Enter 完成，Esc 放回原处。',
  'label.panel.arranging':
    '正在摆放「{title}」：方向键移动，Shift＋方向键调整大小，Enter 完成，Esc 放回原处',
  'label.panel.arranged': '「{title}」就放在这里',
  'label.panel.arrange-cancelled': '「{title}」回到了原处',
  'label.panel.arrange-stuck': '「{title}」不能再往这边动了',
  'label.panel.resize-any': '调整这个面板的大小',
  'label.panel.placed':
    '「{title}」现在在第 {column} 列第 {row} 行，宽 {width}、高 {height}',
  'label.panel.columns': '{count} 列',
  'label.panel.columns-one': '1 列',
  'label.panel.rows': '{count} 行',
  'label.panel.rows-one': '1 行',
  'label.link.new-tab': '（在新标签页中打开）',
  'label.panel.more-rows': '下面还有 {count} 行',
  'label.panel.more-rows-one': '下面还有 1 行',
  'label.panel.more-columns': '右边还有 {count} 列',
  'label.panel.more-columns-one': '右边还有 1 列',
  'label.panel.more-both': '{rows}，{columns}',
  'label.panel.more-below': '下面还有内容',
  'label.panel.awaiting-date': '请选择日期',
  'label.panel.awaiting-date-hint':
    '「{filter}」选了指定日期，挑一天后这里显示那一天的数。',
  'label.dashboard.edit': '编辑',
  'label.dashboard.editing': '正在编辑',
  'label.dashboard.editing-hint': '面板随改随跑；按「保存」才存下这些改动。',
  'label.dashboard.save': '保存',
  'label.dashboard.width': '仪表盘宽度',
  'label.dashboard.width-fixed': '固定宽度',
  'label.dashboard.width-full': '全宽',
  'label.dashboard.add': '添加',
  'label.dashboard.add.data': '数据',
  'label.dashboard.add.content': '内容',
  'label.dashboard.add.saved-view': '已保存的视图…',
  'label.dashboard.add.new-analysis': '新建分析…',
  'label.dashboard.add.heading': '标题',
  'label.dashboard.add.markdown': '文字…',
  'label.dashboard.add.image': '图片…',
  'label.dashboard.add.links': '链接…',
  'label.dashboard.empty.add-view': '添加视图…',
  'label.dashboard.empty.add-heading': '添加标题',
  'label.dashboard.new-heading': '新标题',
  'label.dashboard.added': '已添加「{title}」',
  'label.dashboard.duplicated': '已复制「{title}」',
  'label.dashboard.removed': '已移除「{title}」，按「撤销」可以找回',
  'label.history.undo': '撤销',
  'label.history.redo': '重做',
  'label.history.undo-step': '撤销{what}',
  'label.history.redo-step': '重做{what}',
  'label.history.undone': '已撤销{what}',
  'label.history.redone': '已重做{what}',
  'label.history.add-panel': '添加「{title}」',
  'label.history.remove-panel': '移除「{title}」',
  'label.history.move-panel': '移动「{title}」',
  'label.history.change-panel': '对「{title}」的修改',
  'label.history.add-tab': '添加标签页',
  'label.history.remove-tab': '删除标签页',
  'label.history.change-tabs': '对标签页的修改',
  'label.history.add-filter': '添加筛选「{title}」',
  'label.history.remove-filter': '移除筛选「{title}」',
  'label.history.change-filter': '对筛选「{title}」的修改',
  'label.history.change-grouping': '对时间粒度的修改',
  'label.history.remove-fixed': '移除固定范围',
  'label.history.change-width': '对仪表盘宽度的修改',
  'label.history.change-board': '上一步修改',
  'label.dashboard.tab.untitled': '标签页 {index}',
  'label.picker.add-heading': '添加已保存的视图',
  'label.picker.replace-heading': '替换「{title}」显示的视图',
  'label.picker.description': '选一个记录视图或分析视图放到这个仪表盘上。',
  'label.picker.search': '按名称搜索',
  'label.picker.kind': '种类',
  'label.picker.kind.all': '全部',
  'label.picker.kind.record': '记录',
  'label.picker.kind.analysis': '分析',
  'label.picker.definition': '数据',
  'label.picker.definition.all': '全部数据',
  'label.picker.on-board': '已在仪表盘上',
  'label.picker.private': '只有你看得到',
  'label.picker.none': '没有符合条件的视图。',
  'label.picker.empty': '还没有已保存的记录视图或分析视图。',
  'label.picker.loading': '正在读取视图…',
  'label.panel.menu': '「{title}」的操作',
  'label.panel.pagination': '「{title}」的分页',
  'label.panel.menu.view': '数据',
  'label.panel.menu.edit': '编辑面板',
  'label.panel.open': '在工作台中打开',
  // 作者为数字卡指明了它所计的视图（`opens`）：整张卡都可按。
  'label.panel.open-number': '在工作台中打开「{name}」',
  'label.panel.refresh': '刷新这个面板',
  'label.panel.export': '导出数据…',
  'label.panel.rename': '改标题',
  'label.panel.edit-presentation': '改这里的展示…',
  'label.panel.edit-content': '改内容…',
  'label.panel.replace': '替换视图…',
  'label.panel.copy-shared': '复制为共享视图并替换…',
  'label.panel.copy-system': '复制为系统视图并替换…',
  'label.panel.duplicate': '复制',
  'label.panel.move-to-tab': '移到标签页',
  'label.panel.arrange-menu': '移动或调整大小',
  'label.panel.step.left': '左移',
  'label.panel.step.right': '右移',
  'label.panel.step.up': '上移',
  'label.panel.step.down': '下移',
  'label.panel.step.wider': '加宽',
  'label.panel.step.narrower': '变窄',
  'label.panel.step.taller': '加高',
  'label.panel.step.shorter': '变矮',
  'label.panel.save-as-view': '另存为视图…',
  'label.panel.remove': '从仪表盘移除',
  'label.panel.reorder': '调整「{title}」的顺序',
  'label.panel.picked': '已拿起面板「{title}」。',
  'label.panel.cancelled': '面板「{title}」留在原处。',
  'label.panel.reordered': '「{title}」现在是第 {index} 个面板，共 {total} 个',
  'label.panel.title-input': '面板标题',
  'label.panel.heading-input': '标题文字',
  'label.panel.way-out.edit': '换成另一个视图，或把它从仪表盘移除。',
  'label.panel.way-out.edit-content': '改一下它的内容，或把它从仪表盘移除。',
  'label.content.markdown.add': '添加文字',
  'label.content.markdown.edit': '编辑文字',
  'label.content.markdown.field': '文字',
  'label.content.markdown.hint':
    '支持 Markdown：**粗体**、列表、[链接](https://…)。',
  'label.content.markdown.placeholder': '在这里写这个仪表盘是做什么的。',
  'label.content.image.add': '添加图片',
  'label.content.image.edit': '编辑图片',
  'label.content.image.src': '图片地址',
  'label.content.image.alt': '描述',
  'label.content.image.alt-hint': '读给看不见图片的人听。',
  'label.content.image.href': '点击时打开（可选）',
  'label.content.image.fit': '显示方式',
  'label.content.image.fit.contain': '完整显示',
  'label.content.image.fit.cover': '铺满裁切',
  'label.content.links.add': '添加链接',
  'label.content.links.edit': '编辑链接',
  'label.content.links.item': '链接 {n}',
  'label.content.links.label': '链接文字',
  'label.content.links.href': '地址',
  'label.content.links.description': '说明（可选）',
  'label.content.links.more': '再加一条',
  'label.content.links.drop': '移除链接 {n}',
  'label.content.title': '面板标题（可选）',
  'label.content.required': '这一项要填。',
  'label.content.submit-add': '添加',
  'label.content.submit-edit': '更新',
  'dashboard.binding.global-duplicate': '{field} 在这个面板上映射了两次。',
  'dashboard.binding.global-unknown': '{field} 不是这个仪表盘的筛选字段。',
  'dashboard.binding.kind-mismatch': '{global} 字段无法筛选 {panel} 字段。',
  'dashboard.binding.missing':
    '这个面板没有带 {field} 筛选，所以不能和其他面板一起筛选。',
  'dashboard.binding.panel-unknown':
    '「{filter}」接的字段，这个面板显示的视图里没有。',
  'dashboard.field.duplicate': '筛选字段 {field} 声明了两次。',
  'dashboard.field.name-empty': '筛选字段需要一个名称。',
  'dashboard.field.name-invalid': '{field} 不是可用的字段名。',
  'dashboard.field.not-one-day':
    '筛选 {field} 只能是一天：日历上的一天，或今天、昨天、前天。',
  'dashboard.field.one-day-not-date':
    '筛选 {field} 不是日期筛选，不能限定为一天。',
  'dashboard.field.not-multiple': '筛选 {field} 只能选一个值，这里有好几个。',
  'dashboard.field.options-empty':
    '筛选 {field} 从自己列的一组值里选，但这一组还是空的。',
  'dashboard.field.required-no-default':
    '筛选 {field} 是必填的，永远要有值，所以需要一个默认值。',
  'dashboard.fields.too-many': '一个仪表盘最多放 {max} 个筛选。',
  'dashboard.filter.unknown': '这个仪表盘没有筛选 {field}。',
  'dashboard.filter.held': '筛选 {field} 由页面设定，这里改不了。',
  'dashboard.scope.unsupported':
    '仪表盘不接受外部条件：请改为锁定或隐藏它的筛选。',
  'dashboard.grouping.kept':
    '这个面板保留自己的时间粒度：它的数据不能按仪表盘选的粒度分组。',
  'dashboard.grouping.unit-duplicate': '时间粒度里 {unit} 出现了两次。',
  'dashboard.grouping.unit-unknown': '{unit} 不是这个仪表盘提供的时间粒度。',
  'dashboard.grouping.units-empty': '时间粒度里没有可选的粒度。',
  'dashboard.grid.unsupported':
    '这个仪表盘的布局用的栅格在这里画不出来；面板按 {columns} 列摆放。',
  'dashboard.heading.too-long': '一个标题最多 {max} 个字符。',
  'dashboard.layout.invalid': '这个面板的位置或尺寸不可用。',
  'dashboard.layout.missing': '这个面板没有位置。',
  'dashboard.layout.out-of-grid': '这个面板超出了网格的 {columns} 列。',
  'dashboard.link.label-empty': '链接需要一个标签。',
  'dashboard.links.too-many': '链接面板最多放 {max} 个链接。',
  'dashboard.markdown.too-long': '一段文字最多 {max} 个字符。',
  'dashboard.panel.definition-unknown':
    '这个面板里的分析所用的数据已经不可用了。',
  'dashboard.panel.failed': '这个面板显示的视图没能打开。',
  'dashboard.panel.id-duplicate': '两个面板共用了 id {id}。',
  'dashboard.panel.id-empty': '面板需要一个 id。',
  'dashboard.panel.kind-unsupported': '这个面板指向的不是记录视图或分析视图。',
  'dashboard.panel.not-owned': '只有只属于这个仪表盘的分析才能另存为视图。',
  'dashboard.panel.not-referenced':
    '这个面板显示的不是你打开着的已保存视图，没有可复制的东西。',
  'dashboard.panel.owned-invalid': '这个面板里的分析不是预期的结构。',
  'dashboard.panel.view-unknown': '这个面板要显示的视图在这个应用里找不到。',
  'dashboard.panel.view-undeclared':
    '「{definition}」里没有这个面板要显示的视图。',
  'dashboard.panel.opens-unknown':
    '这个面板设定在工作台中打开的视图在这个应用里找不到，改为打开面板自己的视图。',
  'dashboard.panel.opens-undeclared':
    '「{definition}」里没有这个面板设定在工作台中打开的视图，改为打开面板自己的视图。',
  'dashboard.panel.opens-elsewhere':
    '这个面板设定在工作台中打开的「{view}」看的是「{definition}」，不是这个面板的数据，改为打开面板自己的视图。',
  'dashboard.panel.opens-invalid':
    '这个面板设定在工作台中打开的视图写得不对，改为打开面板自己的视图。',
  'dashboard.panel.presentation-dropped':
    '这个面板改过的展示已经不适用于它的视图，按视图本来的样子显示。',
  'dashboard.system.non-system-panels':
    '这些面板引用了非系统视图，先把它们发布为系统视图：{panels}',
  'dashboard.panel.scope-too-narrow':
    '这个面板显示的视图并不对这个仪表盘的所有读者开放。',
  'dashboard.panel.source-invalid':
    '这个面板要么显示一个已保存的视图，要么显示只属于这个仪表盘的分析。',
  'dashboard.panel.tab-unknown':
    '这个面板不在这个仪表盘的任何标签页上，先显示在第一个标签页。',
  'dashboard.panel.unavailable':
    '这个面板显示的视图已被删除，或者你没有查看权限。',
  'dashboard.panel.unknown-kind': '这种面板类型不可用。',
  'dashboard.panels.too-many': '一个仪表盘最多放 {max} 个面板。',
  'dashboard.shape.invalid': '仪表盘的这一部分不是预期的结构。',
  'dashboard.tab.id-duplicate': '两个标签页共用了 id {id}。',
  'dashboard.tab.id-empty': '标签页需要一个 id。',
  'dashboard.tab.title-empty': '有一个标签页没有名字，按它的位置称呼。',
  'dashboard.tabs.too-many': '一个仪表盘最多放 {max} 个标签页。',
  'dashboard.url.unsupported-scheme':
    '只能显示 http、https、mailto 和相对链接。',
  'dashboard.width.unknown':
    '这个仪表盘的宽度「{width}」无法识别，先按全宽显示；编辑时可以选固定宽度或全宽。',
} satisfies Record<keyof typeof dashboardMessages, string>;
