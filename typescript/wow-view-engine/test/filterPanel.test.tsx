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
import { DeletionState, filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { fields, select } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('buffers edits and clears until Query, retains unset controls, and undoes without querying', () => {
  const apply = vi.fn();
  function Example() {
    const [value, setValue] = useState(
      configuration(node('GTE', 'amount', { value: 10 })),
    );
    return (
      <FilterPanel
        fields={fields}
        value={value}
        onChange={setValue}
        onApply={value => {
          apply(value);
        }}
      />
    );
  }
  render(<Example />);
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '20' },
  });
  expect(apply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.gte('amount', 20) }),
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
  expect(screen.getByLabelText('订单金额值')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '30' },
  });
  fireEvent.click(screen.getByRole('button', { name: '撤销筛选修改' }));
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '',
  );
  expect(apply).toHaveBeenCalledTimes(2);
});

it('does not silently apply partial or invalid input, or submit on Enter during composition', () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(
        node('BETWEEN', 'amount', { lowerBound: 1, upperBound: 10 }),
      )}
      onApply={apply}
    />,
  );
  const upper = screen.getByLabelText('订单金额上限');
  fireEvent.change(upper, { target: { value: '' } });
  expect(
    (
      screen.getByRole('button', {
        name: '查询',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  fireEvent.keyDown(upper, { key: 'Enter', isComposing: true });
  expect(apply).not.toHaveBeenCalled();
});

it('preserves edits during an asynchronous acknowledgement and replaces controlled values explicitly', async () => {
  let replace!: (value: ReturnType<typeof configuration>) => void;
  let finish!: () => void;
  const apply = vi.fn(
    () =>
      new Promise<void>(resolve => {
        finish = resolve;
      }),
  );
  function Example() {
    const [value, setValue] = useState(
      configuration(node('GTE', 'amount', { value: 10 })),
    );
    replace = setValue;
    return (
      <FilterPanel
        fields={fields}
        value={value}
        onChange={setValue}
        onApply={apply}
      />
    );
  }
  render(<Example />);
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '20' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '30' },
  });
  await act(async () => finish());
  expect(screen.getByLabelText('订单金额值')).toHaveProperty('value', '30');
  act(() => replace(configuration(node('GTE', 'amount', { value: 40 }))));
  expect(screen.getByLabelText('订单金额值')).toHaveProperty('value', '40');
  act(() => replace(configuration(node('GTE', 'amount', { value: 20 }))));
  expect(screen.getByLabelText('订单金额值')).toHaveProperty('value', '20');
});

it('keeps separate panels isolated and allows query-error retry', () => {
  const apply = vi.fn();
  render(
    <>
      <div data-testid="first">
        <FilterPanel
          fields={fields}
          defaultValue={configuration(node('EQ', 'amount', { value: 1 }))}
          onApply={apply}
          queryError="请求失败"
        />
      </div>
      <div data-testid="second">
        <FilterPanel
          fields={fields}
          defaultValue={configuration(node('EQ', 'amount', { value: 2 }))}
          appliedValue={configuration(node('EQ', 'amount', { value: 1 }))}
          onApply={() => {}}
        />
      </div>
    </>,
  );
  const first = within(screen.getByTestId('first'));
  fireEvent.change(first.getByLabelText('订单金额值'), {
    target: { value: '3' },
  });
  expect(
    (
      within(screen.getByTestId('second')).getByLabelText(
        '订单金额值',
      ) as HTMLInputElement
    ).value,
  ).toBe('2');
  fireEvent.click(first.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({ expression: filter.eq('amount', 3) }),
  );
});

it('clears only values while preserving parameters and really clears deletion state', async () => {
  const apply = vi.fn(),
    draft = vi.fn();
  const view = render(
    <FilterPanel
      fields={[{ field: 'createdAt', label: '创建时间', type: 'datetime' }]}
      timeZone="Asia/Shanghai"
      defaultValue={configuration(
        node('BEFORE_TODAY', 'createdAt', {
          time: '09:00',
          ...{
            zoneId: 'Asia/Shanghai',
          },
        }),
      )}
      onApply={apply}
      onChange={draft}
    />,
  );
  fireEvent.change(screen.getByRole('textbox', { name: '创建时间时间' }), {
    target: { value: '' },
  });
  expect(draft.mock.lastCall?.[0].root.props.zoneId).toBe('Asia/Shanghai');
  expect(draft.mock.lastCall?.[0].root.props.time).toBeUndefined();
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
  view.unmount();
  render(
    <FilterPanel
      fields={[]}
      defaultValue={configuration({
        id: crypto.randomUUID(),
        operator: FilterOperator.DELETION,
        component: { name: 'builtin' },
        props: { state: DeletionState.ACTIVE },
      })}
      onApply={apply}
    />,
  );
  await select('删除状态', '清空选择');
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
});

it('restored unapplied controlled drafts must remain pending and undoable', () => {
  const pending = vi.fn();
  render(
    <FilterPanel
      fields={fields}

      defaultValue={configuration(node('EQ', 'amount', { value: 2 }))}
      appliedValue={configuration(node('EQ', 'amount', { value: 1 }))}
      onApply={() => {}}
      onPendingChange={pending}
    />,
  );
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '2',
  );
  expect(pending).toHaveBeenLastCalledWith(true);
  expect(
    (screen.getByRole('button', { name: '撤销筛选修改' }) as HTMLButtonElement)
      .disabled,
  ).toBe(false);
});

it('does not repeat an in-flight expression after editing only its number format', () => {
  const apply = vi.fn();
  render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('EQ', 'amount', { value: 1 }))}
      onApply={apply}
      querying
    />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '1.0' },
  });
  fireEvent.click(screen.getByRole('button', { name: /^查询/ }));
  expect(apply).not.toHaveBeenCalled();
});

it('remounting an already-applied numeric draft must stay synchronized', () => {
  const apply = vi.fn(),
    changed = vi.fn(),
    pending = vi.fn();
  const view = render(
    <FilterPanel
      fields={fields}
      defaultValue={configuration(node('EQ', 'amount', { value: 1 }))}
      onApply={apply}
      onChange={changed}
    />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '2' },
  });
  fireEvent.click(screen.getByRole('button', { name: /^查询/ }));
  const applied = apply.mock.lastCall![0],
    retained = changed.mock.lastCall![0];
  view.unmount();
  render(
    <FilterPanel
      fields={fields}

      defaultValue={retained}
      appliedValue={applied.configuration}
      onApply={apply}
      onPendingChange={pending}
    />,
  );
  expect(pending).toHaveBeenLastCalledWith(false);
});
