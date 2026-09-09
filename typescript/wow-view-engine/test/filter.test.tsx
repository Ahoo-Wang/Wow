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

import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { FieldFilter } from '../src/filter/FieldFilter';
import { FilterSelect } from '../src/filter/FilterSelect';

afterEach(cleanup);

describe('fixed field filter', () => {
  it('clears an enum value and allows selecting it again', async () => {
    const changes: (string | null)[] = [];
    function Example() {
      const [value, setValue] = useState<string | null>(null);
      return (
        <FilterSelect
          label="状态"
          placeholder="不限"
          value={value}
          options={[{ value: 'pending', label: '待处理' }]}
          onValueChange={next => {
            changes.push(next);
            setValue(next);
          }}
          onClear={() => {
            changes.push(null);
            setValue(null);
          }}
        />
      );
    }
    render(<Example />);
    const trigger = screen.getByRole('combobox', { name: '状态' });
    for (const label of ['待处理', '清空选择', '待处理']) {
      fireEvent.click(trigger);
      const option = await screen.findByRole('option', {
        name: label,
        exact: true,
      });
      fireEvent.pointerDown(option, { pointerType: 'mouse' });
      fireEvent.click(option);
      expect(trigger.textContent).toContain(
        label === '清空选择' ? '不限' : label,
      );
    }
    expect(changes).toEqual(['pending', null, 'pending']);
  });

  it('shows the placeholder when no select value is set', () => {
    render(
      <FilterSelect
        label="状态"
        placeholder="不限"
        options={[{ value: 'pending', label: '待处理' }]}
        onValueChange={() => {}}
      />,
    );
    expect(
      screen.getByRole('combobox', { name: '状态' }).textContent,
    ).toContain('不限');
  });

  it('portals the menu outside a host that clips content', async () => {
    const { container } = render(
      <div style={{ overflow: 'hidden', position: 'relative', height: 40 }}>
        <FilterSelect
          label="状态"
          value="pending"
          options={[{ value: 'pending', label: '待处理' }]}
          onValueChange={() => {}}
        />
      </div>,
    );
    fireEvent.click(screen.getByRole('combobox', { name: '状态' }));
    const option = await screen.findByRole('option', { name: '待处理' });
    expect(container.contains(option)).toBe(false);
    expect(option.closest('.fve-root')).not.toBeNull();
  });
  it('changes only its controlled operator and preserves the field and value editor', async () => {
    const changes: string[] = [];
    function Example() {
      const [operator, setOperator] = useState('EQ');
      return (
        <FieldFilter
          field={{ field: 'state.amount', label: '订单金额' }}
          operator={operator}
          operators={[
            { value: 'EQ', label: '等于' },
            { value: 'GTE', label: '大于等于' },
          ]}
          onOperatorChange={value => {
            changes.push(value);
            setOperator(value);
          }}
        >
          <input aria-label="订单金额值" defaultValue="1000" />
        </FieldFilter>
      );
    }
    render(<Example />);
    const trigger = screen.getByRole('combobox', { name: '订单金额操作' });
    fireEvent.click(trigger);
    expect(screen.queryByRole('option', { name: '清空选择' })).toBeNull();
    const option = await screen.findByRole('option', { name: '大于等于' });
    fireEvent.pointerDown(option, { pointerType: 'mouse' });
    fireEvent.click(option);
    expect(changes).toEqual(['GTE']);
    expect(
      screen.getByRole('combobox', { name: '订单金额操作' }).textContent,
    ).toContain('大于等于');
    expect(
      (screen.getByLabelText('订单金额值') as HTMLInputElement).value,
    ).toBe('1000');
    expect(screen.getByText('订单金额')).toBeTruthy();
    expect(screen.queryByRole('combobox', { name: '字段' })).toBeNull();
  });
});
