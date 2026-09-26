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

import { useEffect, useState } from 'react';
import type { ViewSource } from '@ahoo-wang/wow-view-engine';
import { DataWorkbench, zhCN } from '@ahoo-wang/wow-view-engine/ui';
// The engine's stylesheet, and the presets `data-fve-preset` picks from.
import '@ahoo-wang/wow-view-engine/styles.css';
import '@ahoo-wang/wow-view-engine/themes.css';
import { createOrdersEngine } from './ordersEngine.js';
import { ORDERS } from './ordersDefinition.js';

// Step 3: the page. The workbench is the whole list — the view list, the
// filters, the table, saving — so the host renders one component.
export function OrdersPage({ source }: { source: ViewSource }) {
  // One engine per mounted page, disposed with it: it holds the open views
  // and their pending requests.
  const [engine] = useState(() => createOrdersEngine(source));
  useEffect(() => () => engine.dispose(), [engine]);
  return (
    <DataWorkbench
      engine={engine}
      definitionId={ORDERS}
      // Chinese wording and dates; leave both out for English.
      messages={zhCN}
      locale="zh-CN"
    />
  );
}
