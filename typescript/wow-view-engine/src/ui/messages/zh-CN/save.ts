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

import type { saveMessages } from '../save.js';

/**
 * `saveMessages` 的简体中文，逐键对应（`../save.ts`）。
 *
 * `satisfies` 让本区少一个键或多一个键都成为编译错误；
 * 各区在 `../zh-CN.ts` 合成 `zhCN`。
 */
export const saveZhCN = {
  // 保存命令、相关对话框，以及一次写入的结局。
  'label.save.save': '保存',
  'label.save.apply-and-save': '应用并保存',
  'label.save.save-as': '另存为',
  'label.save.revert': '还原',
  'label.save.title': '标题',
  'label.save.audience': '可见范围',
  'label.save.group': '视图操作',
  'label.save.saving': '保存中…',
  'label.save.saved': '已保存',
  'label.save.saved-announce': '视图已保存',
  'label.save.shared-heading': '更新所有人看到的视图？',
  'label.save.shared-description': '这会更新所有人看到的「{title}」。',
  'label.save.shared-confirm': '更新给所有人',
  'label.delete.confirm': '删除「{title}」？',
  'label.delete.consequence': '只删除视图，记录仍然保留。',
  'label.delete.shared-consequence': '用到它的人都会失去它。',
  'label.delete.system-consequence': '删除后，所有人都不再看到这个系统视图。',
  'label.delete.dirty-consequence': '未保存的修改会一并删掉。',
  'label.delete.keep': '保留',
  'label.dialog.cancel': '取消',
  'label.dialog.close': '关闭',
  'label.save-as.heading': '另存为新视图',
  'label.save-as.description': '正在看的这个视图保持原样。',
  'label.save-as.copy-title': '{title} 副本',
  'label.save-as.submit': '创建视图',
  'label.save.first-heading': '保存视图',
  'label.save.first-description': '起个名字，并设定可见范围。',
  'label.conflict.choice': '使用最新版本并放弃你的修改，或者用你的版本覆盖它。',
  'label.conflict.theirs': '使用最新版本',
  'label.conflict.mine': '覆盖为我的版本',
  'label.conflict.copy': '另存为我的副本',
  'label.conflict.confirm-theirs': '使用最新版本？',
  'label.conflict.confirm-mine': '用我的版本覆盖最新版本？',
  'label.conflict.local': '我的版本',
  'label.conflict.remote': '最新版本',
  'label.conflict.summary.record':
    '每页 {pageSize} 条 · {layout} · {columns} 列 · {sorts} 个排序',
  'label.conflict.summary.analysis':
    '{groups} 个维度 · {metrics} 个指标 · 前 {limit} 组',
  'label.conflict.summary.dashboard': '{count} 个面板',
  'label.conflict.summary.dashboard-one': '1 个面板',
  'label.conflict.summary.malformed': '无法读取',
  'label.unknown.leave': '放弃',
  'label.unknown.retry': '重试',
  'label.unknown.hint': '重试会再发同一个请求，不会生效两次。',
  'label.unknown.hint.keeps':
    '重试会再发同一个请求，不会生效两次。放弃只是不再等待，你的修改仍在这里，尚未保存。',
  'label.rejected.dismiss': '知道了',
  'label.leave.heading': '离开这个视图？',
  'label.leave.consequence': '未保存的修改会丢失。',
  'label.leave.stay': '留下',
  'label.leave.leave': '离开',
  'label.leave.save': '保存并离开',
  'label.revert.heading': '回到已保存的那一份？',
  'label.revert.consequence': '未保存的修改会丢失。',
  'label.revert.keep': '继续编辑',
} satisfies Record<keyof typeof saveMessages, string>;
