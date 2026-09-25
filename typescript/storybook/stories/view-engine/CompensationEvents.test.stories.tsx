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
import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import { compensationFetcher } from './compensation.js';
import {
  EXECUTION_FAILED_EVENTS,
  createCompensationEventsEngine,
} from './compensationEvents.js';
import {
  RECORDED_COMPENSATION_EVENTS_HOST,
  installRecordedCompensationEvents,
} from './compensationEventsService.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { readColumn } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * Engine regression fixture on the compensation domain: a workbench over the
 * event streams a recorded compensation service appended to its failed
 * executions — the newest events, one execution's history in order, and how
 * the events distribute over types and time, record and analysis views in
 * one list.
 *
 * What it guards is the engine's reading of an event stream: its events sit
 * inside an array, which a column reads by its elements' types, a condition
 * reaches by element match, an analysis expands to count events rather than
 * streams, and the detail lays out element by element — payload no field
 * declares included — plus dates read back from an aggregation as dates.
 *
 * It is not the product. The compensation console reads an execution's
 * history through its own definition (`compensation/dashboard/src/views/`)
 * and has no event-stream analysis page; the definition here
 * (`compensationEvents.ts`) drifts from the product's on purpose. It used to
 * be the 「真实后端/补偿控制台/事件流分析台」 scene against a live service.
 */

/** The workbench over the recorded event streams. */
function Fixture() {
  const [fetcher] = useState(() =>
    compensationFetcher(RECORDED_COMPENSATION_EVENTS_HOST),
  );
  return (
    <StoryEngine create={() => createCompensationEventsEngine(fetcher)}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={EXECUTION_FAILED_EVENTS}
          {...HOST_LANGUAGE}
        />
      )}
    </StoryEngine>
  );
}

const meta = {
  title: 'View Engine/回归夹具/补偿/事件流',
  component: Fixture,
  tags: ['!dev', '!autodocs', 'test'],
  parameters: {
    // Exercised as a host's screen: the workbench fills the page area
    // inside the host's bar and navigation (`AppShell`).
    layout: 'fullscreen',
  },
  beforeEach: installRecordedCompensationEvents,
  decorators: [
    Story => (
      <AppShell service={{ fixture: '录制的补偿服务 · 八条事件流' }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Fixture>;

export default meta;

type Story = StoryObj<typeof meta>;

export const EventStreams: Story = {
  name: '事件流',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const view = (title: string) =>
      canvas.getByRole('button', { name: new RegExp(`^${title}`) });

    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '最近的事件',
      '执行历史',
      '重试成功',
      '人工干预',
      '事件类型分布',
      '每月事件量',
      '每日事件量',
      '每日重试成功',
      '重试最多的执行',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(view('最近的事件')).toHaveAttribute('aria-current', 'true');

    // The newest events first, whichever execution appended them.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, '事件流 ID')).toEqual([
        'EF-1-v5',
        'EF-1-v4',
        'EF-2-v2',
        'EF-3-v1',
        'EF-1-v3',
        'EF-1-v2',
        'EF-2-v1',
        'EF-1-v1',
      ]),
    );
    // Each stream reads by what happened in it — its events by type, in the
    // type's words — though the page asked for the types alone.
    await expect(readColumn(table, '事件')).toEqual([
      '重试成功',
      '准备重试',
      '标记可恢复性',
      '首次失败',
      '重试失败',
      '准备重试',
      '首次失败',
      '首次失败',
    ]);

    // One stream read whole: its event laid out in the detail — the type,
    // the declared fields, and the payload no field declares, key by key.
    table.querySelector<HTMLElement>('tbody tr:last-child')!.focus();
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    const [event] = detail.querySelectorAll<HTMLElement>(
      '[data-slot="detail-element"]',
    );
    await expect(within(event!).getByText('第 1 项')).toBeVisible();
    await expect(within(event!).getByText('首次失败')).toBeVisible();
    await expect(within(event!).getByText('errorMsg')).toBeVisible();
    await expect(
      await within(event!).findByText('Inventory refused.'),
    ).toBeVisible();
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );

    // The history template, still unfilled, reads every history in order.
    await userEvent.click(view('执行历史'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '事件流 ID')).toEqual([
        'EF-1-v1',
        'EF-1-v2',
        'EF-1-v3',
        'EF-1-v4',
        'EF-1-v5',
        'EF-2-v1',
        'EF-2-v2',
        'EF-3-v1',
      ]),
    );

    // A condition on an event reaches into the stream's array.
    await userEvent.click(view('重试成功'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '事件流 ID')).toEqual([
        'EF-1-v5',
      ]),
    );

    // The analysis counts events, one bar per type the streams hold.
    await userEvent.click(view('事件类型分布'));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(5));

    // The execution retried most, and when it last was — the latest of its
    // retry events' times, read as a date and not as epoch milliseconds.
    await userEvent.click(view('重试最多的执行'));
    await waitFor(() =>
      expect(readColumn(canvas.getByRole('table'), '执行 ID')).toEqual([
        'EF-1',
      ]),
    );
    const [latest] = readColumn(canvas.getByRole('table'), '最近一次重试');
    await expect(latest).toMatch(/\d{4}年\d{1,2}月\d{1,2}日/);
    await expect(latest).not.toMatch(/^\d{12,}$/);
  },
};
