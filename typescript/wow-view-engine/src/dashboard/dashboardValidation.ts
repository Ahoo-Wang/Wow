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

import {
  validateFilterConfigurationStructure,
  validateFilterJson,
} from '../filter/filterConfigurationValidation.js';
import {
  assertObject,
  assertText,
  assertPath,
  validateReference,
} from '../contracts/validation/validationPrimitives.js';
import { safeUrl } from '../lib/safeUrl.js';
import { encodeViewResourceId } from '../contracts/viewServiceContract.js';
import type { DashboardConfig } from './dashboardModel.js';

function keys(value: Record<string, unknown>, allowed: readonly string[]) {
  if (Object.keys(value).some(key => !allowed.includes(key)))
    throw new Error('仪表盘配置包含未知属性');
}
function path(value: unknown) {
  assertPath(value, '映射字段');
  if (
    (value as string)
      .split('.')
      .some(part => ['__proto__', 'prototype', 'constructor'].includes(part))
  )
    throw new Error('映射字段包含危险路径');
}
/** Inspect only tree edges, before invoking the existing recursive filter validator. */
export function validateDashboardFilterBudget(root: unknown): void {
  const queue = [{ node: root, depth: 1 }];
  let count = 0;
  while (queue.length) {
    const { node, depth } = queue.pop()!;
    if (++count > 512 || depth > 32)
      throw new Error('仪表盘筛选超过深度或节点预算');
    assertObject(node, '筛选节点');
    if (Array.isArray(node.operands))
      for (const child of node.operands)
        queue.push({ node: child, depth: depth + 1 });
    if (node.predicate !== undefined)
      queue.push({ node: node.predicate, depth: depth + 1 });
  }
}
/** Local admission; references are authorized and checked for nested dashboards when loaded. */
export function validateDashboardConfig(
  value: unknown,
  complete = true,
  maxConfigBytes = 262144,
  limits: { maxPanels?: number; maxFilters?: number } = {},
): asserts value is DashboardConfig {
  assertObject(value, '仪表盘配置');
  keys(value, ['schemaVersion', 'panels', 'filters']);
  if (value.schemaVersion !== 1) throw new Error('不支持的仪表盘配置版本');
  if (
    !Array.isArray(value.panels) ||
    value.panels.length > (limits.maxPanels ?? 12)
  )
    throw new Error('仪表盘面板数量无效');
  if (
    !Array.isArray(value.filters) ||
    value.filters.length > (limits.maxFilters ?? 32)
  )
    throw new Error('仪表盘全局筛选数量无效');
  const panels = new Set<string>();
  const viewPanels = new Set<string>();
  for (const panel of value.panels) {
    assertObject(panel, '面板');
    assertText(panel.id, '面板 ID');
    if (panel.kind === 'view') {
      keys(panel, ['kind', 'id', 'instanceId', 'layout']);
      encodeViewResourceId(panel.instanceId);
      viewPanels.add(panel.id);
    } else if (panel.kind === 'markdown') {
      keys(panel, ['kind', 'id', 'title', 'content', 'layout']);
      assertText(panel.title, '卡片标题');
      if (
        typeof panel.content !== 'string' ||
        new TextEncoder().encode(panel.content).byteLength > 65536
      )
        throw new Error('Markdown内容必须是最多64KiB的文本');
    } else if (panel.kind === 'link' || panel.kind === 'image') {
      const image = panel.kind === 'image';
      keys(
        panel,
        image
          ? ['kind', 'id', 'title', 'src', 'alt', 'caption', 'layout']
          : ['kind', 'id', 'title', 'href', 'description', 'layout'],
      );
      assertText(panel.title, '卡片标题');
      if (
        !safeUrl(
          image ? panel.src : panel.href,
          image ? ['http:', 'https:'] : undefined,
        )
      )
        throw new Error('卡片地址无效或使用了不支持的协议');
      if (image && typeof panel.alt !== 'string')
        throw new Error('图片替代文字必须是文本');
      const description = image ? panel.caption : panel.description;
      if (description !== undefined && typeof description !== 'string')
        throw new Error('卡片说明必须是文本');
    } else throw new Error('未知仪表盘卡片类型');
    if (panels.has(panel.id)) throw new Error('面板 ID 重复');
    panels.add(panel.id);
    assertObject(panel.layout, '面板布局');
    keys(panel.layout, ['x', 'y', 'w', 'h']);
    const { x, y, w, h } = panel.layout;
    if (
      ![x, y, w, h].every(
        value => typeof value === 'number' && Number.isSafeInteger(value),
      )
    )
      throw new Error('面板坐标与尺寸必须为整数');
    if (
      (x as number) < 0 ||
      (y as number) < 0 ||
      (w as number) < 1 ||
      (w as number) > 12 ||
      (x as number) + (w as number) > 12 ||
      (h as number) < 1 ||
      (h as number) > 100 ||
      (y as number) + (h as number) > 10000
    )
      throw new Error('面板坐标或尺寸超出网格边界');
  }
  const filters = new Set<string>();
  for (const filter of value.filters) {
    assertObject(filter, '全局筛选');
    keys(filter, ['id', 'filters', 'bindings', 'excludedPanelIds']);
    assertText(filter.id, '全局筛选 ID');
    if (filters.has(filter.id)) throw new Error('全局筛选 ID 重复');
    filters.add(filter.id);
    assertObject(filter.filters, '筛选配置');
    validateDashboardFilterBudget(filter.filters.root);
    validateFilterConfigurationStructure(filter.filters);
    if (
      !Array.isArray(filter.bindings) ||
      !Array.isArray(filter.excludedPanelIds)
    )
      throw new Error('筛选绑定必须是数组');
    const decided = new Set<string>();
    const decide = (id: unknown) => {
      if (typeof id !== 'string' || !viewPanels.has(id) || decided.has(id))
        throw new Error('筛选绑定重复或引用未知面板');
      decided.add(id);
    };
    filter.excludedPanelIds.forEach(decide);
    for (const binding of filter.bindings) {
      assertObject(binding, '筛选绑定');
      decide(binding.panelId);
      if (binding.kind === 'fields') {
        keys(binding, ['panelId', 'kind', 'fields', 'semanticCompatibility']);
        if (binding.semanticCompatibility !== true)
          throw new Error('字段映射必须声明语义兼容');
        assertObject(binding.fields, '字段映射');
        for (const [source, target] of Object.entries(binding.fields)) {
          path(source);
          path(target);
        }
      } else if (binding.kind === 'transform') {
        keys(binding, ['panelId', 'kind', 'name', 'options']);
        validateReference(binding);
      } else throw new Error('未知筛选绑定类型');
    }
    if (complete && decided.size !== viewPanels.size)
      throw new Error('请为每个面板绑定筛选或明确排除');
  }
  validateFilterJson(value);
  if (
    new TextEncoder().encode(JSON.stringify(value)).byteLength > maxConfigBytes
  )
    throw new Error('仪表盘配置超过字节预算');
}
