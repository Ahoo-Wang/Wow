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
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { DashboardView } from '../../src/dashboard/DashboardView.js';
import { DashboardContent } from '../../src/dashboard/DashboardContent.js';
import { dashboardSetup } from './runtimeFixtures.js';
vi.hoisted(() =>
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  ),
);
afterEach(cleanup);
async function addCard(name: string) {
  fireEvent.click(screen.getByRole('button', { name: '添加', exact: true }));
  fireEvent.click(await screen.findByRole('menuitem', { name, exact: true }));
}

it.each([
  ['markdown', 'Markdown', 'Markdown 内容', '**重点**'],
  ['link', '链接', '链接地址', 'https://example.com/report'],
  ['image', '图片', '图片地址', 'https://example.com/chart.png'],
] as const)(
  'adds, edits, saves and removes %s without discovery or data queries',
  async (kind, label, field, value) => {
    const { engine, host, paged, load } = dashboardSetup({
      schemaVersion: 1,
      panels: [],
      filters: [],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    render(<DashboardView runtime={runtime} />);
    const add = screen.getByRole('button', { name: '添加', exact: true });
    expect(add.closest('header')).not.toBeNull();
    expect(add.querySelector('svg')).not.toBeNull();
    await addCard(`添加${label}`);
    fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
      target: { value: '取消的草稿' },
    });
    fireEvent.click(screen.getByRole('button', { name: '取消', exact: true }));
    await waitFor(() => expect(document.activeElement).toBe(add));
    expect(runtime.getSnapshot().session.dirty).toBe(false);
    await addCard(`添加${label}`);
    fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
      target: { value: '说明卡片' },
    });
    fireEvent.change(screen.getByRole('textbox', { name: field }), {
      target: { value },
    });
    if (kind === 'link')
      fireEvent.change(screen.getByRole('textbox', { name: '说明（可选）' }), {
        target: { value: '链接说明' },
      });
    if (kind === 'image') {
      fireEvent.change(screen.getByRole('textbox', { name: '替代文字' }), {
        target: { value: '趋势图' },
      });
      fireEvent.change(screen.getByRole('textbox', { name: '图注（可选）' }), {
        target: { value: '每周趋势' },
      });
    }
    fireEvent.click(screen.getByRole('button', { name: '添加内容' }));
    expect(runtime.getSnapshot().config.panels[0].kind).toBe(kind);
    await screen.findByRole('heading', { name: '说明卡片' });
    if (kind === 'link')
      expect(runtime.getSnapshot().config.panels[0]).toMatchObject({
        description: '链接说明',
      });
    if (kind === 'image')
      expect(runtime.getSnapshot().config.panels[0]).toMatchObject({
        alt: '趋势图',
        caption: '每周趋势',
      });
    fireEvent.click(screen.getByRole('button', { name: '编辑布局' }));
    fireEvent.click(screen.getByRole('button', { name: '编辑面板1' }));
    fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
      target: { value: '更新卡片' },
    });
    fireEvent.click(screen.getByRole('button', { name: '更新内容' }));
    await act(async () => {
      await engine.save('dashboard');
    });
    expect(vi.mocked(host.instance.save).mock.calls[0][0]).toMatchObject({
      config: { panels: [{ kind, title: '更新卡片' }] },
    });
    expect(screen.queryByRole('button', { name: '取消布局编辑' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '编辑布局' }));
    fireEvent.click(screen.getByRole('button', { name: '移除面板1' }));
    expect(runtime.getSnapshot().config.panels).toHaveLength(0);
    expect(paged).not.toHaveBeenCalled();
    expect(load).not.toHaveBeenCalled();
    cleanup();
    engine.dispose();
  },
);

it('renders CommonMark safely and recovers image failures when its URL changes', () => {
  const base = {
    id: 'content',
    title: '说明',
    layout: { x: 0, y: 0, w: 6, h: 8 },
  };
  const view = render(
    <DashboardContent
      panel={{
        ...base,
        kind: 'markdown',
        content:
          '**重点** ![坏图](javascript:bad) [危险](javascript:alert%281%29) <img src="x" onerror="alert(1)"> [指南](https://example.com)',
      }}
    />,
  );
  expect(view.container.querySelector('strong')?.textContent).toBe('重点');
  expect(view.container.querySelector('img')).toBeNull();
  expect(screen.getByRole('alert').tagName).toBe('SPAN');
  expect(screen.queryByRole('link', { name: '危险' })).toBeNull();
  expect(screen.getByRole('link', { name: '指南' }).getAttribute('rel')).toBe(
    'noopener noreferrer',
  );
  view.rerender(
    <DashboardContent
      panel={{
        ...base,
        kind: 'image',
        src: 'https://example.com/old.png',
        alt: '趋势图',
      }}
    />,
  );
  fireEvent.error(screen.getByRole('img'));
  expect(screen.getByRole('alert').textContent).toContain('图片无法加载');
  view.rerender(
    <DashboardContent
      panel={{
        ...base,
        kind: 'image',
        src: 'https://example.com/new.png',
        alt: '趋势图',
      }}
    />,
  );
  expect(screen.getByRole('img').getAttribute('src')).toContain('new.png');
});

it('readonly content renders without editing controls and rejects unsafe draft URLs accessibly', async () => {
  const config = {
    schemaVersion: 1 as const,
    panels: [
      {
        id: 'link',
        kind: 'link' as const,
        title: '指南',
        href: 'https://example.com',
        layout: { x: 0, y: 0, w: 6, h: 8 },
      },
    ],
    filters: [],
  };
  const read = dashboardSetup(config, {
    permission: {
      getInstance: () => ({
        save: false,
        saveAsPersonal: false,
        saveAsShared: false,
      }),
    },
  });
  await read.engine.load();
  render(<DashboardView runtime={read.engine.dashboard('dashboard')} />);
  await screen.findByRole('link', { name: '指南' });
  expect(
    screen.queryByRole('button', { name: '添加', exact: true }),
  ).toBeNull();
  cleanup();
  read.engine.dispose();
  const edit = dashboardSetup({ schemaVersion: 1, panels: [], filters: [] });
  await edit.engine.load();
  render(<DashboardView runtime={edit.engine.dashboard('dashboard')} />);
  await addCard('添加链接');
  fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
    target: { value: '危险' },
  });
  fireEvent.change(screen.getByRole('textbox', { name: '链接地址' }), {
    target: { value: 'javascript:alert(1)' },
  });
  fireEvent.click(screen.getByRole('button', { name: '添加内容' }));
  await waitFor(() =>
    expect(screen.getByRole('alert').textContent).toContain('有效链接'),
  );
  expect(edit.engine.dashboard('dashboard').getSnapshot().session.dirty).toBe(
    false,
  );
  cleanup();
  edit.engine.dispose();
});

it('discards content dialogs across runtime switches and editor restoration', async () => {
  const first = dashboardSetup({ schemaVersion: 1, panels: [], filters: [] });
  const second = dashboardSetup({ schemaVersion: 1, panels: [], filters: [] });
  await first.engine.load();
  await second.engine.load();
  const view = render(
    <DashboardView runtime={first.engine.dashboard('dashboard')} />,
  );
  await addCard('添加Markdown');
  fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
    target: { value: '旧仪表盘草稿' },
  });
  view.rerender(
    <DashboardView runtime={second.engine.dashboard('dashboard')} />,
  );
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(
    second.engine.dashboard('dashboard').getSnapshot().config.panels,
  ).toHaveLength(0);
  await addCard('添加Markdown');
  fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
    target: { value: '恢复前草稿' },
  });
  await act(async () => {
    await second.engine.restore('dashboard');
  });
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(second.engine.dashboard('dashboard').getSnapshot().session.dirty).toBe(
    false,
  );
  expect(
    second.engine.dashboard('dashboard').getSnapshot().config.panels,
  ).toHaveLength(0);
  cleanup();
  first.engine.dispose();
  second.engine.dispose();
});

it.each(['remove', 'replace', 'layout'] as const)(
  'guards open content editor against concurrent %s',
  async change => {
    const original = {
      id: 'note',
      kind: 'markdown' as const,
      title: '原始内容',
      content: 'initial',
      layout: { x: 0, y: 0, w: 6, h: 8 },
    };
    const { engine, paged } = dashboardSetup({
      schemaVersion: 1,
      panels: [original],
      filters: [],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    render(<DashboardView runtime={runtime} />);
    fireEvent.click(screen.getByRole('button', { name: '编辑布局' }));
    fireEvent.click(screen.getByRole('button', { name: '编辑面板1' }));
    fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
      target: { value: '我的草稿' },
    });
    act(() =>
      runtime.edit(config => ({
        ...config,
        panels:
          change === 'remove'
            ? []
            : [
                {
                  ...original,
                  ...(change === 'replace'
                    ? { content: 'external replacement' }
                    : { layout: { ...original.layout, h: 10 } }),
                },
              ],
      })),
    );
    const before = runtime.getSnapshot().config;
    fireEvent.click(screen.getByRole('button', { name: '更新内容' }));
    if (change === 'layout') {
      expect(runtime.getSnapshot().config.panels[0]).toMatchObject({
        title: '我的草稿',
        layout: { h: 10 },
      });
      expect(screen.queryByRole('dialog')).toBeNull();
    } else {
      expect(screen.getByRole('alert').textContent).toContain(
        '面板已被移除或内容已更改',
      );
      expect(runtime.getSnapshot().config).toEqual(before);
    }
    expect(paged).not.toHaveBeenCalled();
    cleanup();
    engine.dispose();
  },
);

it('cancels with Escape and appends content below existing data without querying', async () => {
  const { engine, paged } = dashboardSetup({
    schemaVersion: 1,
    panels: [
      {
        kind: 'view',
        id: 'a',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 6, h: 5 },
      },
    ],
    filters: [],
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await waitFor(() =>
    expect(runtime.getSnapshot().panels.a.status).toBe('ready'),
  );
  const position = runtime.getSnapshot().panels.a.position;
  paged.mockClear();
  render(<DashboardView runtime={runtime} />);
  await addCard('添加Markdown');
  fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
    target: { value: '取消草稿' },
  });
  fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(runtime.getSnapshot().session.dirty).toBe(false);
  await addCard('添加Markdown');
  fireEvent.change(screen.getByRole('textbox', { name: '标题' }), {
    target: { value: '工作说明' },
  });
  fireEvent.change(screen.getByRole('textbox', { name: 'Markdown 内容' }), {
    target: { value: '**已确认**' },
  });
  fireEvent.click(screen.getByRole('button', { name: '添加内容' }));
  expect(runtime.getSnapshot().config.panels[1]).toMatchObject({
    kind: 'markdown',
    layout: { y: 5 },
  });
  expect(runtime.getSnapshot().panels.a.position).toBe(position);
  expect(paged).not.toHaveBeenCalled();
  cleanup();
  engine.dispose();
});
