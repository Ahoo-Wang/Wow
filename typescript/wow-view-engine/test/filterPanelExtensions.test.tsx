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
import { filter, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterEditorProps } from '../src/filter/filterReactTypes.js';
import { fields, builtinCompiler } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('blocks stale valid custom values and rejects changed field bindings', () => {
  const apply = vi.fn();
  function Custom({ onChange, onValidityChange }: FilterEditorProps) {
    return (
      <>
        <button onClick={() => onValidityChange(false)}>无效输入</button>
        <button onClick={() => onChange({ binding: 'status', value: 'wrong' })}>
          改字段
        </button>
        <button
          onClick={() => {
            onChange({ value: 0 });
            onValidityChange(true);
          }}
        >
          零
        </button>
      </>
    );
  }
  render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'custom' } }, fields[1]]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
      )}
      onApply={apply}
      extensions={{
        filters: {
          custom: {
            ...builtinCompiler,
            component: Custom,
            modes: ['simple', 'advanced'],
            compile: (props, context) =>
              filter.eq(
                (props.binding as string) ?? context.field!.field,
                props.value as number,
              ),
          },
        },
      }}
    />,
  );
  fireEvent.click(screen.getByText('无效输入'));
  expect(
    (
      screen.getByRole('button', {
        name: '查询',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
  fireEvent.click(screen.getByText('改字段'));
  expect(screen.getByRole('alert').textContent).toContain('字段');
  fireEvent.click(screen.getByText('零'));
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).toHaveBeenCalledWith(
    expect.objectContaining({ expression: filter.eq('amount', 0) }),
  );
});

it.each(['value', 'filter'] as const)(
  'reports missing custom editors and contains %s rendering errors',
  renderMode => {
    const broken = [{ ...fields[0], editor: { name: 'missing' } }];
    const { unmount } = render(
      <FilterPanel
        fields={broken}
        defaultValue={configuration(
          node('EQ', 'amount', { value: 1 }, { name: 'missing' }),
        )}
        onApply={() => {}}
      />,
    );
    expect(screen.getByRole('alert').textContent).toContain('missing');
    unmount();
    function Broken(): never {
      throw new Error('editor crash');
    }
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(
      <FilterPanel
        fields={broken}
        defaultValue={configuration(
          node('EQ', 'amount', { value: 1 }, { name: 'missing' }),
        )}
        onApply={() => {}}
        extensions={{
          filters: {
            missing: {
              ...builtinCompiler,
              component: Broken,
              render: renderMode,
              modes: ['simple', 'advanced'],
            },
          },
        }}
      />,
    );
    expect(screen.getByRole('alert').textContent).toContain('editor crash');
    fireEvent.click(screen.getByRole('button', { name: '使用内置编辑器' }));
    expect(screen.getByLabelText('订单金额值')).toBeTruthy();
    log.mockRestore();
  },
);

it('keeps saved component identity explicit and reports an unsupported mode', () => {
  function Custom({ onValidityChange }: FilterEditorProps) {
    useEffect(() => onValidityChange(true), [onValidityChange]);
    return <span>业务编辑器</span>;
  }
  const view = render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'custom' } }]}
      value={configuration(
        node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
      )}
      onChange={() => {}}
      onApply={() => {}}
      editors={{ [FilterOperator.EQ]: { name: 'not-used' } }}
      extensions={{
        filters: {
          custom: { ...builtinCompiler, component: Custom, modes: ['simple'] },
        },
      }}
    />,
  );
  expect(screen.getByText('业务编辑器')).toBeTruthy();
  expect(
    (
      screen.getByRole('button', {
        name: '查询',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(false);
  view.rerender(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'custom' } }]}
      value={configuration(
        node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
        'advanced',
      )}
      onChange={() => {}}
      onApply={() => {}}

      extensions={{
        filters: {
          custom: { ...builtinCompiler, component: Custom, modes: ['simple'] },
        },
      }}
    />,
  );
  expect(screen.queryByText('业务编辑器')).toBeNull();
  expect(screen.getByRole('alert').textContent).toContain('不支持当前模式');
  expect(
    screen.getByRole('button', { name: '查询', exact: true }),
  ).toHaveProperty('disabled', true);
});

it('blocks a failing extension compatibility predicate instead of applying stale state', () => {
  render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'custom' } }]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 1 }, { name: 'custom' }),
      )}
      onApply={() => {}}
      extensions={{
        filters: {
          custom: {
            ...builtinCompiler,
            component: () => null,
            modes: ['simple'],
            supports: () => {
              throw new Error('unsupported editor state');
            },
          },
        },
      }}
    />,
  );
  expect(screen.getByRole('alert').textContent).toContain(
    'unsupported editor state',
  );
  expect(
    (
      screen.getByRole('button', {
        name: '查询',
        exact: true,
      }) as HTMLButtonElement
    ).disabled,
  ).toBe(true);
});
