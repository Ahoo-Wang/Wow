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
import { expect, it, vi } from 'vitest';
import { validateDashboardConfig } from '../../src/dashboard/dashboardValidation.js';
import type {
  DashboardConfig,
  DashboardContentPanel,
} from '../../src/dashboard/dashboardModel.js';
import { dashboardSetup, globalFilter } from './runtimeFixtures.js';
const layout = { x: 0, y: 0, w: 6, h: 6 };
const content: DashboardContentPanel[] = [
  {
    id: 'md',
    kind: 'markdown',
    title: 'Notes',
    content: '# Status\n\nReady',
    layout,
  },
  {
    id: 'link',
    kind: 'link',
    title: 'Orders',
    href: '/orders',
    description: 'Open orders',
    layout,
  },
  {
    id: 'image',
    kind: 'image',
    title: 'Process',
    src: 'https://example.com/process.png',
    alt: 'Order process',
    caption: 'Current process',
    layout,
  },
];
it('validates content discriminants and URLs without treating content as filter targets', () => {
  const config: DashboardConfig = {
    schemaVersion: 1,
    panels: content,
    filters: [],
  };
  expect(() =>
    validateDashboardConfig(JSON.parse(JSON.stringify(config))),
  ).not.toThrow();
  for (const invalid of [
    { ...content[0], content: 42 },
    { ...content[0], content: '中'.repeat(22000) },
    { ...content[1], href: 'javascript:alert(1)' },
    { ...content[1], href: 'java\nscript:alert(1)' },
    { ...content[1], description: {} },
    { ...content[2], src: 'data:image/svg+xml,<svg/>' },
    { ...content[2], src: 'mailto:user@example.com' },
    { ...content[2], alt: null },
    { ...content[2], caption: [] },
    { ...content[0], kind: 'html' },
    { ...content[0], instanceId: 'child' },
  ])
    expect(() =>
      validateDashboardConfig({ ...config, panels: [invalid] }),
    ).toThrow();
  expect(() =>
    validateDashboardConfig({
      ...config,
      filters: [{ ...globalFilter(), bindings: [], excludedPanelIds: ['md'] }],
    }),
  ).toThrow();
  expect(() =>
    validateDashboardConfig({ ...config, panels: [content[0], content[0]] }),
  ).toThrow();
});
it('loads, edits, refreshes and saves static-only dashboards without resolving data', async () => {
  const { engine, host, load, paged } = dashboardSetup({
    schemaVersion: 1,
    panels: content,
    filters: [],
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  expect(runtime.getSnapshot().panels).toEqual({});
  runtime.edit(config => ({
    ...config,
    panels: config.panels.map(panel =>
      panel.kind === 'markdown' ? { ...panel, content: 'Updated' } : panel,
    ),
  }));
  expect(runtime.getSnapshot().pending).toBe(false);
  await runtime.refresh();
  await runtime.apply();
  await engine.save('dashboard');
  expect(load).not.toHaveBeenCalled();
  expect(host.definition!.load).not.toHaveBeenCalled();
  expect(host.resolveSource).not.toHaveBeenCalled();
  expect(paged).not.toHaveBeenCalled();
  expect(vi.mocked(host.instance!.save!).mock.calls[0][0].config).toMatchObject(
    { panels: [{ content: 'Updated' }, { kind: 'link' }, { kind: 'image' }] },
  );
  engine.dispose();
});
it('keeps scoped data results and positions when content is edited or removed', async () => {
  const view = { kind: 'view' as const, id: 'a', instanceId: 'child', layout };
  const scoped = {
    ...globalFilter(),
    bindings: globalFilter().bindings.slice(0, 1),
  };
  const { engine, paged } = dashboardSetup({
    schemaVersion: 1,
    panels: [view, ...content],
    filters: [scoped],
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().panels.a.status).toBe('ready'),
  );
  const position = runtime.getSnapshot().panels.a.position;
  const result = position!.getSnapshot().result;
  paged.mockClear();
  runtime.edit(config => ({
    ...config,
    panels: config.panels.map(panel =>
      panel.kind === 'link' ? { ...panel, title: 'Changed' } : panel,
    ),
  }));
  expect(runtime.getSnapshot().pending).toBe(false);
  runtime.edit(config => ({
    ...config,
    panels: config.panels.filter(panel => panel.id !== 'image'),
  }));
  await engine.save('dashboard');
  expect(runtime.getSnapshot().panels.a.position).toBe(position);
  expect(position!.getSnapshot().result).toBe(result);
  expect(paged).not.toHaveBeenCalled();
  runtime.edit(config => ({
    ...config,
    panels: config.panels.map(panel =>
      panel.id === 'a' ? { ...content[0], id: 'a' } : panel,
    ),
  }));
  expect(runtime.getSnapshot().panels.a).toBeUndefined();
  expect(runtime.getSnapshot().config.filters[0].bindings).toEqual([]);
  engine.dispose();
});
