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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
  act,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { keyboardOrder } from './fixtures/listOrder.js';
import { ListOrder, ListOrderItem } from '../src/lib/ListOrder.js';

afterEach(cleanup);
function Fixture({
  disabled = false,
  owner = 'fixture',
  supplied,
  canMove,
  commit,
  changed = () => {},
}: {
  disabled?: boolean;
  owner?: string;
  supplied?: { id: string; title?: string }[];
  canMove?(a: { id: string }, b: { id: string }): boolean;
  commit?(items: { id: string; title?: string }[]): void | Promise<boolean>;
  changed?(ids: string[]): void;
}) {
  const [local, setItems] = useState([{ id: 'a' }, { id: 'b' }, { id: 'c' }]);
  const items = supplied ?? local;
  return (
    <ListOrder
      owner={owner}
      canMove={canMove}
      disabled={disabled}
      items={items}
      titleOf={item => ('title' in item && item.title) || item.id}
      onChange={
        commit ??
        (next => {
          setItems(next);
          changed(next.map(item => item.id));
        })
      }
    >
      <ol aria-label="顺序">
        {items.map(item => (
          <ListOrderItem key={item.id} id={item.id}>
            {bindings => (
              <>
                <button ref={bindings.handleRef} {...bindings.handleProps}>
                  {item.id}
                </button>
              </>
            )}
          </ListOrderItem>
        ))}
      </ol>
    </ListOrder>
  );
}
it('moves once per key with focus and an announcement', async () => {
  const changed = vi.fn();
  render(<Fixture changed={changed} />);
  const a = screen.getByRole('button', { name: 'a' });
  a.focus();
  await keyboardOrder(a, 'ArrowDown');
  expect(changed).toHaveBeenCalledExactlyOnceWith(['b', 'a', 'c']);
  expect(document.activeElement).toBe(a);
  await waitFor(() =>
    expect(
      screen.getByRole('status', { name: '排序结果' }).textContent,
    ).toContain('第 2 项'),
  );
});
it('cancels a grabbed move without writing and keeps ordinary arrows passive', async () => {
  const changed = vi.fn();
  render(<Fixture changed={changed} />);
  const handle = screen.getByRole('button', { name: 'a' });
  fireEvent.keyDown(handle, { key: 'ArrowDown' });
  expect(changed).not.toHaveBeenCalled();
  await keyboardOrder(handle, 'ArrowDown', true);
  expect(changed).not.toHaveBeenCalled();
});
it('rejects disabled moves and isolates repeated IDs in separate providers', async () => {
  const first = vi.fn(),
    second = vi.fn();
  render(
    <>
      <section aria-label="一">
        <Fixture disabled changed={first} />
      </section>
      <section aria-label="二">
        <Fixture changed={second} />
      </section>
    </>,
  );
  await keyboardOrder(
    within(screen.getByRole('region', { name: '一' })).getByRole('button', {
      name: 'a',
    }),
    'ArrowDown',
  );
  await keyboardOrder(
    within(screen.getByRole('region', { name: '二' })).getByRole('button', {
      name: 'a',
    }),
    'ArrowDown',
  );
  expect(first).not.toHaveBeenCalled();
  expect(second).toHaveBeenCalledOnce();
});

for (const reason of ['permission', 'owner', 'membership', 'qualification'])
  it(`cancels when ${reason} changes during a grabbed move`, async () => {
    const changed = vi.fn();
    const view = render(<Fixture changed={changed} />);
    await keyboardOrder(
      screen.getByRole('button', { name: 'a' }),
      'ArrowDown',
      false,
      () => {
        view.rerender(
          <Fixture
            changed={changed}
            disabled={reason === 'permission'}
            owner={reason === 'owner' ? 'other' : 'fixture'}
            supplied={
              reason === 'membership' ? [{ id: 'b' }, { id: 'c' }] : undefined
            }
            canMove={reason === 'qualification' ? () => false : undefined}
          />,
        );
      },
    );
    expect(changed).not.toHaveBeenCalled();
  });
it('commits the latest objects instead of a gesture snapshot', async () => {
  const commit = vi.fn();
  const view = render(<Fixture commit={commit} />);
  await keyboardOrder(
    screen.getByRole('button', { name: 'a' }),
    'ArrowDown',
    false,
    () =>
      view.rerender(
        <Fixture
          commit={commit}
          supplied={[{ id: 'a', title: 'new' }, { id: 'b' }, { id: 'c' }]}
        />,
      ),
  );
  expect(commit).toHaveBeenCalledExactlyOnceWith(
    [{ id: 'b' }, { id: 'a', title: 'new' }, { id: 'c' }],
    { id: 'a', from: 0, to: 1 },
  );
});
it('reports a thrown write error without announcing success', async () => {
  render(
    <Fixture
      commit={() => {
        throw new Error('write failed');
      }}
    />,
  );
  await keyboardOrder(screen.getByRole('button', { name: 'a' }), 'ArrowDown');
  expect(screen.getByRole('alert').textContent).toBe('write failed');
  expect(screen.getByRole('status', { name: '排序结果' }).textContent).toBe('');
});
it('ignores a late completion after leaving and returning to an owner', async () => {
  let finish!: (success: boolean) => void;
  const commit = vi.fn(
    () =>
      new Promise<boolean>(resolve => {
        finish = resolve;
      }),
  );
  const view = render(<Fixture commit={commit} />);
  await keyboardOrder(screen.getByRole('button', { name: 'a' }), 'ArrowDown');
  view.rerender(<Fixture owner="other" commit={commit} />);
  view.rerender(<Fixture commit={commit} />);
  await act(async () => finish(true));
  expect(screen.getByRole('status', { name: '排序结果' }).textContent).toBe('');
});
