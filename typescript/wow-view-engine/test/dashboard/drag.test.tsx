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
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { GridLayoutProps } from 'react-grid-layout';
import type * as Rgl from 'react-grid-layout';
import { DashboardLayout } from '../../src/dashboard/DashboardGrid.js';
const rgl = vi.hoisted(() => ({ props: {} as GridLayoutProps, width: 1200 }));
vi.mock('react-grid-layout', async importOriginal => ({
  ...(await importOriginal<typeof Rgl>()),
  useContainerWidth: () => ({
    width: rgl.width,
    containerRef: { current: null },
  }),
  default: (props: GridLayoutProps) => {
    rgl.props = props;
    return <div>{props.children}</div>;
  },
}));
afterEach(() => {
  cleanup();
  rgl.width = 1200;
  vi.restoreAllMocks();
});
const panels = ['a', 'b'].map((id, index) => ({
  id,
  kind: 'view' as const,
  instanceId: id,
  layout: { x: index * 6, y: 0, w: 6, h: 5 },
}));
function setup(enabled = true) {
  const commit = vi.fn();
  const view = render(
    <DashboardLayout
      panels={panels}
      enabled={enabled}
      title={id => id}
      onCommit={commit}
    >
      {panel => <input aria-label={panel.id} defaultValue="preserved" />}
    </DashboardLayout>,
  );
  return { ...view, commit };
}
function start() {
  rgl.props.onDragStart?.(
    rgl.props.layout!,
    null,
    rgl.props.layout![0],
    null,
    new MouseEvent('mousedown'),
    document.body,
  );
}
it('commits completed geometry once; ignores intermediate library layout changes', () => {
  const { commit } = setup();
  act(start);
  const next = rgl.props.layout!.map(item => ({ ...item, h: 7 }));
  act(() => rgl.props.onLayoutChange?.(next));
  expect(commit).not.toHaveBeenCalled();
  act(() =>
    rgl.props.onDragStop?.(
      next,
      null,
      null,
      null,
      new MouseEvent('mouseup'),
      document.body,
    ),
  );
  expect(commit).toHaveBeenCalledOnce();
  expect(commit.mock.calls[0][0][0].layout.h).toBe(7);
});
it('Escape closes gesture without commit or mutating library callback values', async () => {
  const { commit } = setup();
  const next = rgl.props.layout!.map(item => Object.freeze({ ...item, h: 7 }));
  const stop = () =>
    rgl.props.onDragStop?.(
      next,
      null,
      null,
      null,
      new MouseEvent('mouseup'),
      document.body,
    );
  document.addEventListener('mouseup', stop, { once: true });
  act(start);
  await act(async () => {
    fireEvent.keyDown(document, { key: 'Escape' });
  });
  expect(commit).not.toHaveBeenCalled();
  expect(rgl.props.layout![0].h).toBe(5);
  expect(screen.getByRole('status').textContent).toMatch(/已取消/);
});
it('readonly disables interaction; mobile keeps keyboard editing without emitting responsive changes', () => {
  const read = setup(false);
  expect(rgl.props.dragConfig?.enabled).toBe(false);
  expect(rgl.props.resizeConfig?.enabled).toBe(false);
  expect(screen.queryByRole('button', { name: '移动a' })).toBeNull();
  read.unmount();
  rgl.width = 390;
  const { commit } = setup();
  expect(rgl.props.dragConfig?.enabled).toBe(false);
  expect(commit).not.toHaveBeenCalled();
  fireEvent.keyDown(screen.getByRole('button', { name: '调整a尺寸' }), {
    key: 'ArrowDown',
  });
  expect(commit.mock.calls[0][0][0].layout.h).toBe(6);
  expect(screen.queryByRole('spinbutton')).toBeNull();
});
