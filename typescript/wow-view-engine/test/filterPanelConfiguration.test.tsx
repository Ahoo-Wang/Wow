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
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterComponentProps } from '../src/filter/filterReactTypes.js';
import { compileFilterConfiguration } from '../src/filter/filterCore.js';
import { fields } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('adding an unset component leaves the applied query synchronized without a request', async () => {
  const apply = vi.fn(),
    pending = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('GTE', 'amount', { value: 10 }))}
      onApply={apply}
      onPendingChange={pending}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
  const dialog = await screen.findByRole('dialog', { name: '选择筛选字段' });
  fireEvent.click(within(dialog).getByRole('checkbox', { name: '订单状态' }));
  expect(screen.getByLabelText('订单状态值')).toBeTruthy();
  expect(pending).toHaveBeenLastCalledWith(false);
  expect(apply).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('订单状态值'), {
    target: { value: 'paid' },
  });
  fireEvent.click(screen.getByRole('button', { name: '撤销筛选修改' }));
  expect((screen.getByLabelText('订单状态值') as HTMLInputElement).value).toBe(
    '',
  );
});

it('passes opaque properties to the renderer and keeps non-query changes synchronized', () => {
  const apply = vi.fn(),
    changed = vi.fn(),
    pending = vi.fn();
  function Custom({ props, onChange, onClear }: FilterComponentProps) {
    return (
      <button
        onClick={() => onChange({ ...props, displayLabel: '修改后的显示文字' })}
        data-clearable={!!onClear}
      >
        {String(props.displayLabel)}
      </button>
    );
  }
  const draft = {
    ...node('EQ', 'amount', { value: 1 }),
    component: { name: 'custom' },
    props: { selected: 1, displayLabel: '显示文字' },
  };
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(draft)}
      appliedValue={configuration(draft)}

      onApply={apply}
      onChange={changed}
      onPendingChange={pending}
      extensions={{
        filters: {
          custom: {
            component: Custom,
            render: 'filter',
            modes: ['simple'],
            compile: props => filter.eq('amount', props.selected as number),
          },
        },
      }}
    />,
  );
  const button = screen.getByRole('button', { name: '显示文字' });
  expect(button.getAttribute('data-clearable')).toBe('false');
  fireEvent.click(button);
  expect(changed.mock.lastCall?.[0].root.props).toEqual({
    selected: 1,
    displayLabel: '修改后的显示文字',
  });
  expect(pending).toHaveBeenLastCalledWith(false);
  expect(apply).not.toHaveBeenCalled();
});

it('clear values retains the controls, groups and stable identities', () => {
  const changed = vi.fn();
  const draft = {
    ...node('AND'),
    operands: [
      node('EQ', 'amount', { value: 1 }),
      node('EQ', 'status', { value: 'paid' }),
    ],
  };
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(draft)}

      onApply={() => {}}
      onChange={changed}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '清空条件' }));
  const result = changed.mock.lastCall![0];
  expect(result.root.id).toBe(draft.id);
  expect(result.root.operands.map((node: { id: string }) => node.id)).toEqual(
    draft.operands!.map(node => node.id),
  );
  expect(compileFilterConfiguration(result, fields).expression).toEqual(
    filter.matchAll(),
  );
});

it('keeps an uncompiled custom draft visible and blocks Query without its compiler', () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}

      defaultValue={configuration({
        id: 'unknown',
        operator: Op.EQ,
        field: 'amount',
        component: { name: 'missing' },
        props: { selected: 1 },
      })}
      onApply={apply}
    />,
  );
  expect(
    screen
      .getAllByRole('alert')
      .some(item => item.textContent?.includes('missing')),
  ).toBe(true);
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).not.toHaveBeenCalled();
});

it('restores the selected type of an unset scalar after JSON removed undefined properties', () => {
  render(
    <FilterPanel
      fields={fields}

      defaultValue={configuration({
        id: 'typed',
        operator: Op.EQ,
        field: 'items',
        component: { name: 'builtin' },
        props: { value: { type: 'number' } },
      })}
      onApply={() => {}}
    />,
  );
  expect(
    screen.getByRole('combobox', { name: '商品明细值类型' }).textContent,
  ).toContain('数值');
  expect((screen.getByLabelText('商品明细值') as HTMLInputElement).value).toBe(
    '',
  );
});

it('does not duplicate an in-flight query after adding only an unset control', async () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('EQ', 'amount', { value: 1 }))}
      onApply={apply}
      querying
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '添加筛选' }));
  fireEvent.click(
    within(
      await screen.findByRole('dialog', { name: '选择筛选字段' }),
    ).getByRole('checkbox', { name: '订单状态' }),
  );
  fireEvent.click(screen.getByRole('button', { name: /^查询/ }));
  expect(apply).not.toHaveBeenCalled();
});
