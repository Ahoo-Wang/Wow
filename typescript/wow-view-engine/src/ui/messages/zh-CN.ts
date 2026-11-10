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

import type { en } from './en.js';
import { saveZhCN } from './zh-CN/save.js';
import { headerZhCN } from './zh-CN/header.js';
import { recordZhCN } from './zh-CN/record.js';
import { refreshZhCN } from './zh-CN/refresh.js';
import { exportZhCN } from './zh-CN/export.js';
import { filterZhCN } from './zh-CN/filter.js';
import { configZhCN } from './zh-CN/config.js';
import { scopeZhCN } from './zh-CN/scope.js';
import { viewZhCN } from './zh-CN/view.js';
import { manageZhCN } from './zh-CN/manage.js';
import { analysisZhCN } from './zh-CN/analysis.js';
import { chartZhCN } from './zh-CN/chart.js';
import { chartReadingZhCN } from './zh-CN/chartReading.js';
import { chartIssuesZhCN } from './zh-CN/chartIssues.js';
import { dashboardZhCN } from './zh-CN/dashboard.js';
import { buildingZhCN } from './zh-CN/building.js';
import { filtersZhCN } from './zh-CN/filters.js';
import { clicksZhCN } from './zh-CN/clicks.js';
import { embedZhCN } from './zh-CN/embed.js';
import { datasetsZhCN } from './zh-CN/datasets.js';
import { statusZhCN } from './zh-CN/status.js';
import { definitionZhCN } from './zh-CN/definition.js';
import { capabilitiesZhCN } from './zh-CN/capabilities.js';
import { workbenchZhCN } from './zh-CN/workbench.js';
import { renderZhCN } from './zh-CN/render.js';
import { bulkZhCN } from './zh-CN/bulk.js';
import { actionsZhCN } from './zh-CN/actions.js';
import { reorderZhCN } from './zh-CN/reorder.js';

/**
 * 简体中文文案，与 `en` 逐键对应。
 *
 * `satisfies Record<keyof typeof en, string>`（即 `MessageKey`）让少一个键或多一个键都成为编译错误，
 * 类型取自 `en` 本身，因为文案目录在 `kit` 之下、不向上引用。
 * `test/messages.test.tsx` 另外核对每句里的 `{param}` 占位符。
 *
 * 文案按区分文件（`zh-CN/`），与 `en` 的各区同名、同序：每区
 * `satisfies Record<keyof typeof <区>Messages, string>`，少一个键或多一个键
 * 就在出错的那一区报出来，这里只把各区合起来。
 *
 * 宿主要改其中几句，把它铺开再覆盖即可：
 * `messages={{ ...zhCN, 'label.filter.apply': '确定' }}`。
 */
export const zhCN: Readonly<Record<string, string>> = {
  ...saveZhCN,
  ...headerZhCN,
  ...recordZhCN,
  ...refreshZhCN,
  ...exportZhCN,
  ...filterZhCN,
  ...configZhCN,
  ...scopeZhCN,
  ...viewZhCN,
  ...manageZhCN,
  ...analysisZhCN,
  ...chartZhCN,
  ...chartReadingZhCN,
  ...chartIssuesZhCN,
  ...dashboardZhCN,
  ...buildingZhCN,
  ...filtersZhCN,
  ...clicksZhCN,
  ...embedZhCN,
  ...datasetsZhCN,
  ...statusZhCN,
  ...definitionZhCN,
  ...capabilitiesZhCN,
  ...workbenchZhCN,
  ...renderZhCN,
  ...bulkZhCN,
  ...actionsZhCN,
  ...reorderZhCN,
} satisfies Record<keyof typeof en, string>;
