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

import { FilterOperator as Op, filter } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { useLayoutEffect } from 'react';
import type { FilterEditorProps } from '../src/filter/filterReactTypes.js';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterConfiguration } from '../src/filter/filterModel.js';
import { fields } from './fixtures/filterPanel.js';

afterEach(cleanup);
const initial: FilterConfiguration = {
  mode: 'simple',
  root: {
    id: 'amount',
    operator: Op.EQ,
    field: 'amount',
    component: { name: 'builtin' },
    props: { value: 1 },
  },
};

it('applies canonical properties and acknowledges an older submission without replacing newer edits', async () => {
  let acknowledge!: () => void;
  const apply = vi.fn(
    () =>
      new Promise<void>(resolve => {
        acknowledge = resolve;
      }),
  );
  render(
    <FilterPanel fields={fields} defaultValue={initial} onApply={apply} />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '2' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledWith({
    configuration: {
      ...initial,
      root: {
        ...initial.root,
        props: { value: { type: 'number', value: '2' } },
      },
    },
    expression: filter.eq('amount', 2),
  });
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '3' },
  });
  await act(async () => acknowledge());
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '3',
  );
  expect(screen.getByRole('status').textContent).toBe('待查询');
  fireEvent.click(screen.getByRole('button', { name: '撤销筛选修改' }));
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '2',
  );
});

it('controlled configuration emits changes without maintaining a second editing value', () => {
  const change = vi.fn();
  const view = render(
    <FilterPanel
      fields={fields}
      value={initial}
      onChange={change}
      onApply={() => {}}
    />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '4' },
  });
  expect(change.mock.lastCall?.[0].root.props.value).toEqual({
    type: 'number',
    value: '4',
  });
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '1',
  );
  view.rerender(
    <FilterPanel
      fields={fields}
      value={change.mock.lastCall![0]}
      onChange={change}
      onApply={() => {}}
    />,
  );
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '4',
  );
});

it.each([
  { ...initial.root, operator: Op.IN, props: { values: 'invalid' } },
  { ...initial.root, operator: Op.BEFORE_TODAY, props: { time: false } },
  {
    ...initial.root,
    operator: Op.SEARCH,
    field: undefined,
    props: { query: 'x', fields: 'invalid' },
  },
])(
  'keeps malformed builtin collection properties visible as validation errors',
  root => {
    const apply = vi.fn();
    render(
      <FilterPanel
        fields={fields}
        defaultValue={{ mode: 'advanced', root }}
        onApply={apply}
      />,
    );
    expect(screen.getAllByRole('alert').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
    expect(apply).not.toHaveBeenCalled();
  },
);

it('keeps the newer successful application when an older submission rejects', async () => {
  const operations: { resolve(): void; reject(error: Error): void }[] = [];
  const apply = vi.fn(
    () =>
      new Promise<void>((resolve, reject) =>
        operations.push({ resolve, reject }),
      ),
  );
  render(
    <FilterPanel fields={fields} defaultValue={initial} onApply={apply} />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '2' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '3' },
  });
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledTimes(2);
  await act(async () => operations[1].resolve());
  await act(async () => operations[0].reject(new Error('old submission')));
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('');
  expect(screen.getByLabelText('订单金额值')).toHaveProperty('value', '3');
});

it('keeps failed application edits pending and allows retry', async () => {
  const apply = vi
    .fn()
    .mockRejectedValueOnce(new Error('query rejected'))
    .mockResolvedValueOnce(undefined);
  render(
    <FilterPanel fields={fields} defaultValue={initial} onApply={apply} />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '2' },
  });
  await act(async () =>
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true })),
  );
  expect(screen.getByRole('alert').textContent).toContain('query rejected');
  expect(screen.getByRole('status').textContent).toBe('待查询');
  await act(async () =>
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true })),
  );
  expect(apply).toHaveBeenCalledTimes(2);
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('');
});

it('retains validity reported by a replacement editor layout effect', () => {
  let reported = false;
  function Custom({ props, onValidityChange }: FilterEditorProps) {
    useLayoutEffect(() => {
      if (props.value === 2 && !reported) {
        reported = true;
        onValidityChange(false, 'replacement buffer incomplete');
      }
    }, [props, onValidityChange]);
    return <span>custom buffer</span>;
  }
  const value = {
    ...initial,
    root: { ...initial.root, component: { name: 'custom' } },
  };
  const extensions = {
    filters: {
      custom: {
        component: Custom,
        modes: ['simple'] as const,
        compile: (props: FilterEditorProps['props']) =>
          filter.eq('amount', props.value as number),
      },
    },
  };
  const view = render(
    <FilterPanel
      fields={fields}
      value={value}
      onChange={() => {}}
      onApply={() => {}}
      extensions={extensions}
    />,
  );
  view.rerender(
    <FilterPanel
      fields={fields}
      value={{ ...value, root: { ...value.root, props: { value: 2 } } }}
      onChange={() => {}}
      onApply={() => {}}
      extensions={extensions}
    />,
  );
  expect(
    screen.getByRole('button', { name: '查询', exact: true }),
  ).toHaveProperty('disabled', true);
  expect(screen.getByRole('alert').textContent).toContain(
    'replacement buffer incomplete',
  );
});

it('recovers a crashed renderer when the same node receives corrected external properties', () => {
  const log = vi.spyOn(console, 'error').mockImplementation(() => {});
  function Custom({ props }: FilterEditorProps) {
    if (props.value === -1) throw new Error('invalid restored value');
    return (
      <input
        aria-label="recovered value"
        value={String(props.value)}
        readOnly
      />
    );
  }
  const value = {
    ...initial,
    root: {
      ...initial.root,
      component: { name: 'custom' },
      props: { value: -1 },
    },
  };
  const extensions = {
    filters: {
      custom: {
        component: Custom,
        modes: ['simple'] as const,
        compile: (props: FilterEditorProps['props']) =>
          filter.eq('amount', props.value as number),
      },
    },
  };
  const view = render(
    <FilterPanel
      fields={fields}
      value={value}
      onChange={() => {}}
      onApply={() => {}}
      extensions={extensions}
    />,
  );
  expect(screen.getByRole('button', { name: '使用内置编辑器' })).toBeTruthy();
  view.rerender(
    <FilterPanel
      fields={fields}
      value={{ ...value, root: { ...value.root, props: { value: 1 } } }}
      onChange={() => {}}
      onApply={() => {}}
      extensions={extensions}
    />,
  );
  expect(screen.getByLabelText('recovered value')).toHaveProperty('value', '1');
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByRole('button', { name: '使用内置编辑器' })).toBeNull();
  log.mockRestore();
});
