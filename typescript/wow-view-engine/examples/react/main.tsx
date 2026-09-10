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

import { ThemesExample } from './ThemesExample.js';
import { BuiltinCellsExample } from './BuiltinCellsExample.js';
import { BuiltinFiltersExample } from './BuiltinFiltersExample.js';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { OrderExample } from './OrderExample.js';
import { FilterPersistenceExample } from './FilterPersistenceExample.js';

const root = document.getElementById('root');
if (!root) throw new Error('订单示例缺少 root 容器。');
const example = new URLSearchParams(location.search).get('example');
createRoot(root).render(
  <StrictMode>
    {example === 'themes' ? (
      <ThemesExample />
    ) : example === 'builtin-cells' ? (
      <BuiltinCellsExample persist />
    ) : example === 'builtin-filters' ? (
      <BuiltinFiltersExample persist />
    ) : example === 'persistence' ? (
      <FilterPersistenceExample />
    ) : (
      <OrderExample
        scopeKey="local-user:demo-orders"
        persistViews={example === 'local-storage'}
      />
    )}
  </StrictMode>,
);
