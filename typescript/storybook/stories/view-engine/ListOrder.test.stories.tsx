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

import '@ahoo-wang/fetcher-view-engine/styles.css';
import { keyboardOrder } from './listOrder.play.js';
import {
  Dialog,
  DialogContent,
  DialogTitle,
} from '../../packages/view-engine/src/components/ui/dialog.js';
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, within } from 'storybook/test';
import {
  ListOrder,
  ListOrderItem,
} from '../../packages/view-engine/src/lib/ListOrder.js';

const initial = [
  { id: 'a', title: '甲', group: 'normal' },
  { id: 'b', title: '乙', group: 'normal' },
  { id: 'c', title: '丙', group: 'normal' },
  { id: 'fixed', title: '固定', group: 'locked' },
];
function Demo({
  wrap = false,
  asynchronous = false,
  fail = false,
  portal = false,
  scroll = false,
}: {
  wrap?: boolean;
  asynchronous?: boolean;
  fail?: boolean;
  portal?: boolean;
  scroll?: boolean;
}) {
  const [items, setItems] = useState(initial);
  const [other, setOther] = useState(initial.slice(0, 2));
  const [editable, setEditable] = useState(true),
    [busy, setBusy] = useState(false);
  const [owner, setOwner] = useState(0),
    [commits, setCommits] = useState(0),
    [error, setError] = useState('');
  const content = (
    <div className="fve-root fve:p-6">
      <div className="fve:mb-4 fve:flex fve:gap-3">
        <button
          data-action="permission"
          onClick={() => setEditable(value => !value)}
        >
          切换编辑权限
        </button>
        <button
          data-action="title"
          onClick={() =>
            setItems(current =>
              current.map(item =>
                item.id === 'a' ? { ...item, title: '更新后的甲' } : item,
              ),
            )
          }
        >
          外部更新标题
        </button>
        <button
          data-action="remove"
          onClick={() =>
            setItems(current => current.filter(item => item.id !== 'a'))
          }
        >
          外部移除甲
        </button>
        <button
          data-action="reorder"
          onClick={() => setItems(current => [...current].reverse())}
        >
          外部重排
        </button>
        <button
          data-action="owner"
          onClick={() => setOwner(value => value + 1)}
        >
          切换实例
        </button>
      </div>
      <output data-result="order">
        {items.map(item => item.id).join(',')}
      </output>
      <output data-result="commits">{commits}</output>
      {error && <p role="alert">{error}</p>}
      <ListOrder
        items={items}
        owner={owner}
        disabled={!editable || busy}
        titleOf={item => item.title}
        canMove={(a, b) => a.group === b.group}
        onChange={
          asynchronous
            ? async next => {
                setBusy(true);
                await new Promise(resolve => setTimeout(resolve, 150));
                if (fail) {
                  setError('顺序保存失败');
                  setBusy(false);
                  return false;
                }
                setItems(current =>
                  next.map(
                    item => current.find(value => value.id === item.id) ?? item,
                  ),
                );
                setBusy(false);
                setCommits(value => value + 1);
                return true;
              }
            : next => {
                setItems(next);
                setCommits(value => value + 1);
              }
        }
      >
        <ol
          aria-label="主列表"
          style={{
            display: 'flex',
            flexDirection: wrap ? 'row' : 'column',
            flexWrap: wrap ? 'wrap' : 'nowrap',
            gap: 12,
            width: wrap ? 260 : 360,
            padding: 8,
            listStyle: 'none',
            maxHeight: scroll ? 180 : undefined,
            overflowY: scroll ? 'auto' : undefined,
          }}
        >
          {items.map(item => (
            <ListOrderItem
              key={item.id}
              id={item.id}
              data-item={item.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                minHeight: 64,
                padding: 8,
                border: '1px solid',
                width: wrap ? 112 : 340,
              }}
            >
              {bindings => (
                <>
                  <button
                    ref={bindings.handleRef}
                    {...bindings.handleProps}
                    aria-label={`移动${item.title}`}
                    style={{ padding: 12 }}
                  >
                    ⠿
                  </button>
                  <input
                    aria-label={`${item.id}标题`}
                    style={{ width: wrap ? 56 : 180 }}
                    value={item.title}
                    onChange={event =>
                      setItems(current =>
                        current.map(value =>
                          value.id === item.id
                            ? { ...value, title: event.target.value }
                            : value,
                        ),
                      )
                    }
                  />
                </>
              )}
            </ListOrderItem>
          ))}
        </ol>
      </ListOrder>
      <ListOrder
        items={other}
        owner="other"
        titleOf={item => item.title}
        onChange={next => setOther(next)}
      >
        <ol aria-label="独立列表">
          {other.map(item => (
            <ListOrderItem key={item.id} id={item.id}>
              {bindings => (
                <>
                  <button ref={bindings.handleRef} {...bindings.handleProps}>
                    {item.title}
                  </button>
                </>
              )}
            </ListOrderItem>
          ))}
        </ol>
      </ListOrder>
    </div>
  );
  return portal ? (
    <div style={{ minHeight: '100vh' }}>
      <Dialog defaultOpen>
        <DialogContent className="fve:sm:max-w-xl">
          <DialogTitle>排序设置</DialogTitle>
          {content}
        </DialogContent>
      </Dialog>
    </div>
  ) : (
    content
  );
}
const meta = {
  id: 'view-engine-list-order',
  title: 'View Engine/引擎与宿主/排序回归',
  component: Demo,
  tags: ['!dev', '!autodocs', 'test'],
} satisfies Meta<typeof Demo>;
export default meta;
export const Basic: StoryObj<typeof meta> = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement),
      list = within(canvas.getByRole('list', { name: '主列表' }));
    await userEvent.click(list.getByRole('button', { name: '移动甲' }));
    await keyboardOrder(
      list.getByRole('button', { name: '移动甲' }),
      'ArrowDown',
    );
    await expect(
      canvasElement.querySelector('[data-result="order"]'),
    ).toHaveTextContent('b,a,c,fixed');
    await expect(list.getByRole('button', { name: '移动甲' })).toHaveFocus();
  },
};
export const Pointer: StoryObj<typeof meta> = {};
export const Wrapped: StoryObj<typeof meta> = { args: { wrap: true } };
export const AsyncFailure: StoryObj<typeof meta> = {
  args: { asynchronous: true, fail: true },
};

export const Portal: StoryObj<typeof meta> = { args: { portal: true } };
export const Scroll: StoryObj<typeof meta> = { args: { scroll: true } };
export const AsyncSuccess: StoryObj<typeof meta> = {
  args: { asynchronous: true },
};
