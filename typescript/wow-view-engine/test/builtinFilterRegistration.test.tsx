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

import { node, configuration } from './fixtures/filterPanel.js';
import { afterEach, expect, it, vi } from 'vitest';
import { cleanup, render, screen, fireEvent } from '@testing-library/react';
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import {
  createFilterConfiguration,
  compileFilterConfiguration,
  clearFilterValues,
} from '../src/filter/filterConfiguration.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from '../src/filter/filterModel.js';
afterEach(cleanup);
const fields: FilterFieldDefinition[] = [
  {
    field: 'customer',
    label: '客户',
    operators: [Op.IN, Op.NOT_IN],
    editor: { name: 'multi-select' },
    options: [
      { value: 'a', label: '客户甲', group: '客户' },
      { value: 'b', label: '客户乙', group: '客户' },
    ],
  },
];
it('restores and clears built-in component props through the public configuration pipeline without a custom registry', () => {
  const draft: FilterComponentConfig = {
    id: 'customer-filter',
    operator: Op.IN,
    field: 'customer',
    component: { name: 'multi-select' },
    props: {
      values: ['a'],
      selectedOptions: [{ value: 'a', label: '保存名称' }],
    },
  };
  const config = JSON.parse(
    JSON.stringify(createFilterConfiguration(draft, 'simple')),
  );
  expect(compileFilterConfiguration(config, fields).expression).toEqual(
    filter.isIn('customer', ['a']),
  );
  const restored = config.root;
  expect(restored.props?.selectedOptions).toEqual([
    { value: 'a', label: '保存名称' },
  ]);
  const cleared = clearFilterValues(restored, fields);
  expect(cleared.id).toBe(draft.id);
  expect(cleared.component).toEqual(draft.component);
  expect(
    compileFilterConfiguration(
      createFilterConfiguration(cleared, 'simple'),
      fields,
    ).expression,
  ).toEqual(filter.matchAll());
});
it('renders a configured built-in picker in standalone FilterPanel and applies only on Query', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      defaultValue={configuration(
        node('IN', 'customer', { values: ['a'] }, { name: 'multi-select' }),
      )}
      fields={fields}
      onApply={apply}
    />,
  );
  fireEvent.click(await screen.findByRole('combobox', { name: '客户' }));
  fireEvent.click(await screen.findByRole('option', { name: '客户乙' }));
  expect(apply).not.toHaveBeenCalled();
  fireEvent.keyDown(screen.getByRole('combobox', { name: '客户搜索' }), {
    key: 'Escape',
  });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({
      expression: filter.isIn('customer', ['a', 'b']),
    }),
  );
});
it('reports an unregistered remote source as a configuration error', async () => {
  const fields: FilterFieldDefinition[] = [
    {
      field: 'id',
      label: '客户',
      operators: [Op.EQ],
      editor: { name: 'remote-select', options: { source: 'missing' } },
    },
  ];
  render(
    <FilterPanel
      defaultValue={configuration(
        node(
          'EQ',
          'id',
          { value: 'u' },
          { name: 'remote-select', options: { source: 'missing' } },
        ),
      )}
      fields={fields}
      onApply={() => {}}
    />,
  );
  expect(await screen.findByText(/未注册候选数据源/)).toBeTruthy();
  expect(
    (screen.getByRole('button', { name: '查询' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});
