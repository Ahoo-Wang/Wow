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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createPortal } from 'react-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { FilterPanel } from '../src/filter/FilterPanel.js';
import { fields, builtinCompiler } from './fixtures/filterPanel.js';

afterEach(cleanup);

it('queries a valid single-line input with Enter while respecting composition and validation', () => {
  const apply = vi.fn();
  render(
    <div role="dialog" aria-label="宿主对话框">
      <FilterPanel
        fields={fields}
        defaultValue={configuration(node('EQ', 'amount', { value: 10 }))}
        onApply={apply}
      />
    </div>,
  );
  const input = screen.getByRole('textbox', { name: '订单金额值' });
  fireEvent.change(input, { target: { value: '25' } });
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true });
  fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });
  expect(apply).not.toHaveBeenCalled();
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(apply).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ expression: filter.eq('amount', 25) }),
  );
  fireEvent.keyDown(input, { key: 'Enter', repeat: true });
  fireEvent.change(input, { target: { value: 'invalid' } });
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(apply).toHaveBeenCalledTimes(1);
});

it('leaves Enter to selectors, multiline inputs, portals and handled editor keys', () => {
  const apply = vi.fn();
  function Editor() {
    return (
      <>
        <input role="combobox" aria-expanded="true" aria-label="搜索候选" />
        <textarea aria-label="多行值" />
        <select aria-label="选择候选">
          <option>候选项</option>
        </select>
        <input
          aria-label="编辑器处理按键"
          onKeyDown={event => event.preventDefault()}
        />
        {createPortal(<input aria-label="弹层输入" />, document.body)}
      </>
    );
  }
  render(
    <FilterPanel
      fields={[{ ...fields[0], editor: { name: 'keyboard-editor' } }]}
      defaultValue={configuration(
        node('EQ', 'amount', { value: 10 }, { name: 'keyboard-editor' }),
      )}
      onApply={apply}
      extensions={{
        filters: {
          'keyboard-editor': {
            ...builtinCompiler,
            component: Editor,
            modes: ['simple', 'advanced'],
          },
        },
      }}
    />,
  );
  for (const label of [
    '搜索候选',
    '多行值',
    '选择候选',
    '编辑器处理按键',
    '弹层输入',
  ]) {
    fireEvent.keyDown(screen.getByLabelText(label), { key: 'Enter' });
  }
  expect(apply).not.toHaveBeenCalled();
});
