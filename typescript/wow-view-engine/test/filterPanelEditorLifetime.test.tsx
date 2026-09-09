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
import { filter } from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { useLayoutEffect } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type {
  FilterComponentProps,
  FilterEditorProps,
} from '../src/filter/filterReactTypes.js';
import { fields, builtinCompiler } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('merges an asynchronous custom change into the latest tree and ignores it after unmount', () => {
  let publish: FilterEditorProps['onChange'] | undefined;
  const drafts = vi.fn();
  function Custom(props: FilterEditorProps) {
    publish ??= props.onChange;
    return <span>自定义状态</span>;
  }
  const view = render(
    <FilterPanel
      fields={[fields[0], { ...fields[1], editor: { name: 'custom' } }]}
      defaultValue={configuration({
        ...node('AND'),
        operands: [
          node('EQ', 'amount', { value: 1 }),
          node('EQ', 'status', { value: 'pending' }, { name: 'custom' }),
        ],
      })}
      onApply={() => {}}
      onChange={drafts}
      extensions={{
        filters: {
          custom: {
            ...builtinCompiler,
            component: Custom,
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  fireEvent.change(screen.getByLabelText('订单金额值'), {
    target: { value: '2' },
  });
  act(() => publish!({ value: 'paid' }));
  expect((screen.getByLabelText('订单金额值') as HTMLInputElement).value).toBe(
    '2',
  );
  view.unmount();
  const count = drafts.mock.calls.length;
  act(() => publish!({ value: 'closed' }));
  expect(drafts).toHaveBeenCalledTimes(count);
});

it('clearing a value rejects callbacks from the custom editor it just unmounted', async () => {
  let publish: FilterEditorProps['onChange'] | undefined;
  const apply = vi.fn();
  function Custom({ onChange, onClear }: FilterComponentProps) {
    publish ??= onChange;
    return <button onClick={onClear}>清空自定义值</button>;
  }
  render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'custom' } }]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
      )}
      onApply={apply}
      extensions={{
        filters: {
          custom: {
            ...builtinCompiler,
            component: Custom,
            render: 'filter',
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  fireEvent.click(screen.getByRole('button', { name: '清空自定义值' }));
  act(() => publish!({ value: 7 }));
  fireEvent.click(screen.getByRole('button', { name: /^查询/ }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.matchAll() }),
  );
});

it('never revives callbacks when a replaced custom component returns', () => {
  let oldChange: FilterEditorProps['onChange'] | undefined;
  const apply = vi.fn();
  function A({ onChange }: FilterEditorProps) {
    oldChange ??= onChange;
    return <span>A</span>;
  }
  function B() {
    return <span>B</span>;
  }
  function panel(component: typeof A | typeof B) {
    return (
      <FilterPanel
        fields={[{ ...fields[0], editor: { name: 'custom' } }]}
        defaultValue={configuration(
          node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
        )}
        onApply={apply}
        extensions={{
          filters: {
            custom: {
              ...builtinCompiler,
              component,
              modes: ['simple', 'advanced'],
            },
          },
        }}
      />
    );
  }
  const view = render(panel(A));
  view.rerender(panel(B));
  expect(screen.getByText('B')).toBeTruthy();
  view.rerender(panel(A));
  act(() => oldChange!({ value: 7 }));
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenLastCalledWith(
    expect.objectContaining({ expression: filter.eq('amount', 1) }),
  );
});

it('merges a custom layout-effect change into the current controlled draft', () => {
  const changes = vi.fn();
  let published = false;
  function Custom({ props, onChange }: FilterEditorProps) {
    useLayoutEffect(() => {
      if (props.value === 'loaded' && !published) {
        published = true;
        onChange({ value: 'paid' });
      }
    }, [props, onChange]);
    return <span>自定义状态</span>;
  }
  const initial = {
    ...node('AND'),
    operands: [
      node('EQ', 'amount', { value: 1 }),
      node('EQ', 'status', { value: 'pending' }, { name: 'custom' }),
    ],
  };
  const definitions = [fields[0], { ...fields[1], editor: { name: 'custom' } }];
  const extensions = {
    filters: {
      custom: {
        ...builtinCompiler,
        component: Custom,
        modes: ['simple' as const],
      },
    },
  };
  const view = render(
    <FilterPanel
      fields={definitions}

      value={configuration(initial)}
      extensions={extensions}
      onApply={() => {}}
      onChange={changes}
    />,
  );
  const restored = structuredClone(initial);
  restored.operands![0].props.value = 2;
  restored.operands![1].props.value = 'loaded';
  view.rerender(
    <FilterPanel
      fields={definitions}

      value={configuration(restored)}
      extensions={extensions}
      onApply={() => {}}
      onChange={changes}
    />,
  );
  expect(changes).toHaveBeenLastCalledWith(
    configuration({
      ...restored,
      operands: [
        restored.operands![0],
        {
          id: restored.operands![1].id,
          operator: restored.operands![1].operator,
          field: 'status',
          component: { name: 'custom' },
          props: { value: 'paid' },
        },
      ],
    }),
  );
});
