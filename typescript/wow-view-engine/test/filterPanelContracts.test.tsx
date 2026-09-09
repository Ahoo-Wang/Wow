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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { compileFilterConfiguration } from '../src/filter/filterCore.js';
import type { FilterComponentConfig } from '../src/filter/filterModel.js';
import type { FilterEditorProps } from '../src/filter/filterReactTypes.js';
import { builtinCompiler, fields } from './fixtures/filterPanel.js';

afterEach(cleanup);

it.each(['constructor', 'toString', '__proto__'])(
  'applies a valid filter whose stable ID is %s',
  id => {
    const apply = vi.fn();
    render(
      <FilterPanel
        fields={fields}

        defaultValue={configuration({
          id,
          operator: Op.EQ,
          field: 'amount',
          component: { name: 'builtin' },
          props: { value: 10 },
        })}
        onApply={apply}
      />,
    );
    const query = screen.getByRole('button', { name: '查询' });
    expect((query as HTMLButtonElement).disabled).toBe(false);
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(query);
    expect(apply).toHaveBeenCalledWith(
      expect.objectContaining({ expression: filter.eq('amount', 10) }),
    );
  },
);

it.each(['value', 'filter'] as const)(
  'recovers a failed %s extension when its component is replaced',
  renderMode => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const apply = vi.fn();
    function Broken(): never {
      throw new Error('editor crash');
    }
    function Healthy() {
      return <input aria-label="恢复的金额" defaultValue="10" />;
    }
    const panel = (component: typeof Healthy | typeof Broken) => (
      <FilterPanel
        fields={[{ ...fields[0], editor: { name: 'custom' } }]}
        defaultValue={configuration(
          node('EQ', 'amount', { value: 10 }, { name: 'custom' }),
        )}
        onApply={apply}
        extensions={{
          filters: {
            custom: {
              ...builtinCompiler,
              component,
              render: renderMode,
              modes: ['simple', 'advanced'],
            },
          },
        }}
      />
    );
    const view = render(panel(Broken));
    expect(screen.getByRole('alert').textContent).toContain('editor crash');
    view.rerender(panel(Healthy));
    expect(screen.queryByLabelText('恢复的金额')).not.toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查询' }));
    expect(apply).toHaveBeenCalledWith(
      expect.objectContaining({ expression: filter.eq('amount', 10) }),
    );
  },
);

it.each(['single', 'mixed', 'nested'])(
  'disables clearing a %s tree until every extension declares clear semantics',
  shape => {
    const changed = vi.fn();
    let clearCalls = 0;
    function Custom({ props }: FilterEditorProps) {
      return (
        <input
          aria-label="自定义值"
          value={String(props.selected ?? '')}
          readOnly
        />
      );
    }
    const leaf: FilterComponentConfig = {
      id: 'custom',
      operator: Op.EQ,
      field: shape === 'nested' ? 'quantity' : 'amount',
      component: { name: 'custom' },
      props: { selected: 10, displayLabel: '保留' },
    };
    const draft: FilterComponentConfig =
      shape === 'single'
        ? leaf
        : shape === 'mixed'
          ? {
              id: 'group',
              operator: Op.AND,
              operands: [
                {
                  id: 'status',
                  operator: Op.EQ,
                  field: 'status',
                  component: { name: 'builtin' },
                  props: { value: 'paid' },
                },
                leaf,
              ],
              component: { name: 'builtin' },
              props: {},
            }
          : {
              id: 'items',
              operator: Op.ELEMENT_MATCH,
              field: 'items',
              predicate: leaf,
              component: { name: 'builtin' },
              props: {},
            };
    const registration = {
      component: Custom,
      modes: ['simple', 'advanced'] as const,
      compile: (props: FilterEditorProps['props']) =>
        props.selected === undefined
          ? undefined
          : filter.eq(leaf.field!, props.selected as number),
    };
    const panel = (clearable: boolean) => (
      <FilterPanel
        fields={fields}

        defaultValue={configuration(draft)}
        onChange={changed}
        onApply={() => {}}
        extensions={{
          filters: {
            custom: {
              ...registration,
              ...(clearable
                ? {
                    clear: (props: FilterEditorProps['props']) => {
                      clearCalls++;
                      return { ...props, selected: undefined };
                    },
                  }
                : {}),
            },
          },
        }}
      />
    );
    const view = render(panel(false));
    const clear = screen.getByRole('button', { name: '清空条件' });
    expect((clear as HTMLButtonElement).disabled).toBe(true);
    const description = clear.getAttribute('aria-describedby');
    expect(description).toBeTruthy();
    expect(document.getElementById(description!)?.textContent).toBeTruthy();
    fireEvent.click(clear);
    expect(changed).not.toHaveBeenCalled();
    expect((screen.getByLabelText('自定义值') as HTMLInputElement).value).toBe(
      '10',
    );
    view.rerender(panel(true));
    expect(clearCalls).toBe(0);
    expect((clear as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(clear);
    expect(clearCalls).toBe(1);
    expect(
      compileFilterConfiguration(changed.mock.lastCall![0], fields, undefined, {
        custom: registration,
      }).expression,
    ).toEqual(filter.matchAll());
    expect(leaf.props).toEqual({ selected: 10, displayLabel: '保留' });
  },
);
