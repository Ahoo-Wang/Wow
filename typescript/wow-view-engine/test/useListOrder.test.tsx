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
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useListOrder } from '../src/lib/useListOrder.js';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function setup(orientation: 'horizontal' | 'vertical' = 'horizontal') {
  const onChange = vi.fn();
  function List() {
    const items = ['a', 'b', 'c'].map(id => ({ id }));
    const order = useListOrder({
      items,
      onChange,
      titleOf: item => item.id,
      orientation,
    });
    return (
      <ol {...order.listProps}>
        {items.map((item, index) => (
          <li key={item.id}>
            <button {...order.handleProps(index)}>{item.id}</button>
          </li>
        ))}
      </ol>
    );
  }
  render(<List />);
  vi.stubGlobal('DragEvent', MouseEvent);
  screen.getAllByRole('listitem').forEach((item, index) => {
    vi.spyOn(item, 'getBoundingClientRect').mockReturnValue(
      new DOMRect(index === 1 ? 110 : 0, index === 2 ? 50 : 0, 100, 40),
    );
  });
  return onChange;
}

it.each([
  ['a', 190, 20, ['b', 'a', 'c']],
  ['b', 90, 70, ['a', 'c', 'b']],
  ['c', 120, 20, ['a', 'c', 'b']],
])(
  'drops %s at (%i, %i) across a wrapped horizontal list',
  (item, clientX, clientY, expected) => {
    const onChange = setup();
    const dataTransfer = {
      effectAllowed: '',
      dropEffect: '',
      setData: vi.fn(),
    };
    fireEvent.dragStart(screen.getByRole('button', { name: item }), {
      dataTransfer,
    });
    fireEvent.dragOver(screen.getByRole('list'), {
      dataTransfer,
      clientX,
      clientY,
    });
    expect(dataTransfer.dropEffect).toBe('move');
    fireEvent.drop(screen.getByRole('list'), {
      dataTransfer,
      clientX,
      clientY,
    });
    expect(
      onChange.mock.lastCall?.[0].map((entry: { id: string }) => entry.id),
    ).toEqual(expected);
  },
);

it('adds left and right keys only for horizontal lists', () => {
  const horizontal = setup();
  fireEvent.keyDown(screen.getByRole('button', { name: 'b' }), {
    key: 'ArrowLeft',
  });
  expect(
    horizontal.mock.lastCall?.[0].map((item: { id: string }) => item.id),
  ).toEqual(['b', 'a', 'c']);
  fireEvent.keyDown(screen.getByRole('button', { name: 'b' }), {
    key: 'ArrowRight',
  });
  expect(
    horizontal.mock.lastCall?.[0].map((item: { id: string }) => item.id),
  ).toEqual(['a', 'c', 'b']);
  cleanup();
  const vertical = setup('vertical');
  fireEvent.keyDown(screen.getByRole('button', { name: 'b' }), {
    key: 'ArrowLeft',
  });
  expect(vertical).not.toHaveBeenCalled();
  fireEvent.keyDown(screen.getByRole('button', { name: 'b' }), {
    key: 'ArrowDown',
  });
  expect(vertical).toHaveBeenCalledOnce();
});
