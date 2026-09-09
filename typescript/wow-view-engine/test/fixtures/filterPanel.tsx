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

import { fireEvent, screen } from '@testing-library/react';
import {
  compileBuiltinFilter,
  clearBuiltinFilterProps,
} from '../../src/filter/filterCore.js';
import type { FilterFieldDefinition } from '../../src/filter/filterModel.js';

export const fields: FilterFieldDefinition[] = [
  { field: 'amount', label: '订单金额', type: 'number' },
  { field: 'status', label: '订单状态', type: 'string' },
  {
    field: 'items',
    label: '商品明细',
    type: 'array',
    fields: [{ field: 'quantity', label: '数量', type: 'number' }],
  },
];
export async function select(label: string, option: string) {
  fireEvent.click(screen.getByRole('combobox', { name: label }));
  const item = await screen.findByRole('option', { name: option, exact: true });
  fireEvent.pointerDown(item, { pointerType: 'mouse' });
  fireEvent.click(item);
}

export const builtinCompiler = {
  compile: compileBuiltinFilter,
  clear: clearBuiltinFilterProps,
};

export { createFilterConfiguration as configuration } from '../../src/filter/filterConfiguration.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { newFilterNode } from '../../src/filter/filterNodes.js';
import type {
  FilterComponentProperties,
  FilterEditorReference,
} from '../../src/filter/filterModel.js';
export function node(
  operator: keyof typeof FilterOperator,
  field?: string,
  props: FilterComponentProperties = {},
  component?: FilterEditorReference,
) {
  return {
    ...newFilterNode(FilterOperator[operator], field, component),
    props,
  };
}
