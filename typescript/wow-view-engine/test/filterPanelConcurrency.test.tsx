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

import { Suspense, startTransition, useState } from 'react';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { expect, it, vi, afterEach } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterConfiguration } from '../src/filter/filterModel.js';
import type { FilterEditorProps } from '../src/filter/filterReactTypes.js';
import { FilterOperator as Op, filter } from '@ahoo-wang/fetcher-wow';
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
function Custom({ props, onChange }: FilterEditorProps) {
  return (
    <input
      aria-label="Amount值"
      value={String(props.value)}
      onChange={event => onChange({ value: Number(event.target.value) })}
    />
  );
}
it.each([
  { suspend: false, custom: false },
  { suspend: true, custom: false },
  { suspend: false, custom: true },
  { suspend: true, custom: true },
])(
  'committed editor works with suspended replacement = $suspend and custom = $custom',
  ({ suspend, custom }) => {
    const initial: FilterConfiguration = {
      mode: 'simple',
      root: {
        id: 'amount',
        field: 'amount',
        operator: Op.EQ,
        component: { name: custom ? 'custom' : 'builtin' },
        props: { value: 1 },
      },
    };
    const change = vi.fn(),
      apply = vi.fn();
    const pending = new Promise<void>(() => {});
    function Gate({ blocked }: { blocked: boolean }) {
      if (blocked) throw pending;
      return null;
    }
    function App() {
      const [value, setValue] = useState(initial);
      const replace = () =>
        startTransition(() =>
          setValue({
            ...initial,
            root: { ...initial.root, props: { value: 9 } },
          }),
        );
      return (
        <>
          <button onClick={replace}>Replace</button>
          <Suspense fallback={<span>Waiting</span>}>
            <FilterPanel
              fields={[{ field: 'amount', label: 'Amount', type: 'number' }]}
              extensions={{
                filters: {
                  custom: {
                    component: Custom,
                    modes: ['simple'],
                    compile: properties =>
                      filter.eq('amount', properties.value as number),
                  },
                },
              }}
              value={value}
              disabled={value.root.props.value === 9}
              onChange={next => {
                change(next);
                setValue(next);
              }}
              onApply={apply}
            />
            <Gate blocked={value.root.props.value === 9} />
          </Suspense>
        </>
      );
    }
    render(<App />);
    if (suspend)
      fireEvent.click(screen.getByRole('button', { name: 'Replace' }));
    expect((screen.getByLabelText('Amount值') as HTMLInputElement).value).toBe(
      '1',
    );
    fireEvent.change(screen.getByLabelText('Amount值'), {
      target: { value: '2' },
    });
    expect(change).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('Amount值')).toHaveProperty('value', '2');
    fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
    expect(apply).toHaveBeenCalledWith(
      expect.objectContaining({
        expression: { op: Op.EQ, field: 'amount', value: 2 },
      }),
    );
  },
);

it('keeps the committed error recovery button usable during a suspended replacement', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const initial: FilterConfiguration = {
    mode: 'simple',
    root: {
      id: 'amount',
      field: 'amount',
      operator: Op.EQ,
      component: { name: 'custom' },
      props: { value: 1 },
    },
  };
  const change = vi.fn();
  const pending = new Promise<void>(() => {});
  function Broken(): never {
    throw new Error('broken');
  }
  function Gate({ blocked }: { blocked: boolean }) {
    if (blocked) throw pending;
    return null;
  }
  function App() {
    const [value, setValue] = useState(initial);
    return (
      <>
        <button
          onClick={() =>
            startTransition(() =>
              setValue({
                ...initial,
                root: { ...initial.root, props: { value: 9 } },
              }),
            )
          }
        >
          Replace
        </button>
        <Suspense fallback={<span>Waiting</span>}>
          <FilterPanel
            fields={[{ field: 'amount', label: 'Amount', type: 'number' }]}
            extensions={{
              filters: {
                custom: {
                  component: Broken,
                  modes: ['simple'],
                  compile: props => filter.eq('amount', props.value as number),
                },
              },
            }}
            value={value}
            disabled={value.root.props.value === 9}
            onChange={next => {
              change(next);
              setValue(next);
            }}
            onApply={() => {}}
          />
          <Gate blocked={value.root.props.value === 9} />
        </Suspense>
      </>
    );
  }
  render(<App />);
  const fallback = screen.getByRole('button', { name: '使用内置编辑器' });
  fireEvent.click(screen.getByRole('button', { name: 'Replace' }));
  expect(fallback).toHaveProperty('disabled', false);
  fireEvent.click(fallback);
  expect(change).toHaveBeenCalledTimes(1);
  expect(screen.getByLabelText('Amount值')).toHaveProperty('value', '1');
});
