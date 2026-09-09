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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import type { FilterEditorProps } from '../src/filter/filterReactTypes.js';
import { fields, builtinCompiler } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('changing an operator cannot clear a custom editors reported invalid input', async () => {
  const apply = vi.fn();
  function Custom({
    onValidityChange,
  }: {
    onValidityChange(valid: boolean): void;
  }) {
    return (
      <input
        aria-label="自定义金额值"
        defaultValue="1"
        onChange={() => onValidityChange(false)}
      />
    );
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
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  fireEvent.change(screen.getByLabelText('自定义金额值'), {
    target: { value: 'abc' },
  });
  expect(
    (screen.getByRole('button', { name: /^查询/ }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  fireEvent.click(screen.getByRole('combobox', { name: '订单金额操作' }));
  const item = await screen.findByRole('option', { name: '不等于' });
  fireEvent.pointerDown(item, { pointerType: 'mouse' });
  fireEvent.click(item);
  expect(
    (screen.getByLabelText('自定义金额值') as HTMLInputElement).value,
  ).toBe('abc');
  fireEvent.click(screen.getByRole('button', { name: /^查询/ }));
  expect(apply).not.toHaveBeenCalled();
});

it('accepts validity reporting immediately after publishing custom properties', () => {
  const apply = vi.fn();
  function Custom({ onChange, onValidityChange }: FilterEditorProps) {
    return (
      <button
        onClick={() => {
          onChange({ value: 1 });
          onValidityChange(false);
        }}
      >
        继续编辑
      </button>
    );
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
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  fireEvent.click(screen.getByText('继续编辑'));
  expect(
    (screen.getByRole('button', { name: '查询' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
});

it('blocks invalid custom input even when its message is empty', () => {
  const apply = vi.fn();
  function Custom({ onValidityChange }: FilterEditorProps) {
    return (
      <button onClick={() => onValidityChange(false, '')}>输入无效</button>
    );
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
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  fireEvent.click(screen.getByText('输入无效'));
  fireEvent.click(screen.getByRole('button', { name: '查询', exact: true }));
  expect(apply).not.toHaveBeenCalled();
});
