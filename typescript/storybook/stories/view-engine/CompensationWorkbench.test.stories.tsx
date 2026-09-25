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
import { EllipsisVerticalIcon } from 'lucide-react';
import { useState } from 'react';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { RecoverableType } from '@ahoo-wang/wow-client';
import type {
  RecordKey,
  RecordRow,
  ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import {
  useBulkCommand,
  type BulkCommand,
  type BulkRun,
  type BulkSelection,
  type RecordActionSlots,
} from '@ahoo-wang/wow-view-engine/react';
import {
  DataWorkbench,
  formatMessage,
  zhCN,
} from '@ahoo-wang/wow-view-engine/ui';
// View Engine's own primitives, so the added commands look like its own.
import { Button } from '@/ui/components/button';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/ui/components/dropdown-menu';
import { IconButton } from '@/ui/IconButton';
// Popup contents come themed from popups.tsx, as View Engine's own do.
import { DropdownMenuContent } from '@/ui/popups';
import { AppShell } from '../shared/AppShell.js';
import {
  EXECUTION_FAILED,
  compensationCommands,
  compensationFetcher,
  createCompensationEngine,
  type CompensationCommands,
} from './compensation.js';
import {
  RECORDED_COMPENSATION_HOST,
  installRecordedCompensationService,
} from './compensationService.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { readColumn, readTotal } from './readTable.js';
import { chartsDrawn, drawnMarks } from './chartDom.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * Engine regression fixture on the compensation domain: a record workbench
 * whose host hangs its own commands on a row and on a selection
 * (`record.actions`, `useBulkCommand`), over a recorded compensation service
 * whose commands change the recorded executions the way the service does.
 *
 * What it guards is the engine's: a definition whose row commands read
 * fields no column shows (`rowFields`), the bulk status line above the rows,
 * the keyboard path into the detail and back, a condition whose values come
 * from the data (a `TERMS` aggregation), the title bar's phrase search, and
 * the analysis views listed with the record views.
 *
 * It is not the product. The compensation console's definitions and
 * commands live in `compensation/dashboard/src/views/` and its own e2e
 * (`compensation/dashboard/e2e/`, the live service in `e2e/real-server/`)
 * covers the product against a real service. The definition here
 * (`compensation.ts`) is cut to what these stories assert and drifts from the
 * product's on purpose. It used to be the 「真实后端/补偿控制台/快照控制台」
 * scene against a live service; that scene now is the console itself.
 */

const RECOVERABILITY: [RecoverableType, string][] = [
  [RecoverableType.RECOVERABLE, '可恢复'],
  [RecoverableType.UNRECOVERABLE, '不可恢复'],
  [RecoverableType.UNKNOWN, '未知'],
];

/** What an execution's snapshot says about which commands it takes. */
function standing(row: RecordRow) {
  const state = (row.data.state ?? {}) as Record<string, unknown>;
  const settled = state.status === 'SUCCEEDED';
  const retryable = state.isRetryable === true && !settled;
  return {
    // Wow's `prepare_compensation` retries within the spec; past its limit
    // only the forced one does.
    retry: retryable && state.isBelowRetryThreshold === true,
    forceRetry: retryable,
    recoverable: state.recoverable as RecoverableType | undefined,
  };
}

/** The recoverability choices, the one an execution already has left out. */
function RecoverabilityItems({
  current,
  onMark,
}: {
  current?: RecoverableType;
  onMark(value: RecoverableType, label: string): void;
}) {
  return (
    <DropdownMenuGroup>
      <DropdownMenuLabel>标记为</DropdownMenuLabel>
      {RECOVERABILITY.map(([value, label]) => (
        <DropdownMenuItem
          key={value}
          disabled={value === current}
          onClick={() => onMark(value, label)}
        >
          {label}
        </DropdownMenuItem>
      ))}
    </DropdownMenuGroup>
  );
}

/** The commands on one execution, in its row: the operator's everyday path. */
function RowCommands({
  row,
  commands,
  bulk,
  onRun,
}: {
  row: RecordRow;
  commands: CompensationCommands;
  bulk: BulkCommand;
  onRun(command: BulkRun, keys: readonly RecordKey[]): void;
}) {
  const can = standing(row);
  const run = (title: string, each: (id: string) => Promise<void>) =>
    onRun({ title, each: key => each(String(key)) }, [row.key]);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <IconButton
            label={`${String(row.key)} 的操作`}
            variant="ghost"
            size="icon-xs"
            disabled={bulk.running !== null}
          />
        }
      >
        <EllipsisVerticalIcon />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuGroup>
          <DropdownMenuItem
            disabled={!can.retry}
            onClick={() => run('重试', commands.retry)}
          >
            重试
          </DropdownMenuItem>
          <DropdownMenuItem
            disabled={!can.forceRetry}
            onClick={() => run('强制重试', commands.forceRetry)}
          >
            强制重试
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <RecoverabilityItems
          current={can.recoverable}
          onMark={(value, label) =>
            run(`标记为${label}`, id => commands.markRecoverable(id, value))
          }
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** The same commands over a selection, in the toolbar while rows are picked. */
function BulkCommands({
  selection,
  commands,
  bulk,
  onRun,
}: {
  selection: BulkSelection;
  commands: CompensationCommands;
  bulk: BulkCommand;
  onRun(command: BulkRun, selection: BulkSelection): void;
}) {
  const count = selection.keys.length;
  const run = (title: string, each: (id: string) => Promise<void>) =>
    onRun({ title, each: key => each(String(key)) }, selection);
  return (
    <>
      <Button
        size="sm"
        disabled={bulk.running !== null}
        onClick={() => run('重试', commands.retry)}
      >
        {count > 0 ? `重试 ${count} 条` : '重试'}
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={bulk.running !== null}
        onClick={() => run('强制重试', commands.forceRetry)}
      >
        强制重试
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="outline"
              size="sm"
              disabled={bulk.running !== null}
            />
          }
        >
          标记可恢复性
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <RecoverabilityItems
            onMark={(value, label) =>
              run(`标记为${label}`, id => commands.markRecoverable(id, value))
            }
          />
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

/**
 * The default workbench with the compensation commands hung in its slots.
 *
 * The fixture adds no markup of its own besides the commands: everything a
 * business page needs — what to do to one execution or a selection of them,
 * and what came of it — reaches the workbench through `record`, which is the
 * route an application takes when the default look is right and only the
 * commands are its own. What a command does to one execution is all the
 * console writes; `useBulkCommand` runs it over the rows a few at a time,
 * says how far it has come and why each refusal happened, and leaves the
 * refused rows selected — and the workbench says all of that above the rows
 * (`record.bulk`). A row and a selection run through the one command, so
 * one line says what either did.
 */
function ConsoleWorkbench({
  engine,
  commands,
}: {
  engine: ViewEngine;
  commands: CompensationCommands;
}) {
  const bulk = useBulkCommand();
  const actions: RecordActionSlots = {
    row: ({ row, refresh }) => (
      <RowCommands
        row={row}
        commands={commands}
        bulk={bulk}
        // A row's command leaves the selection as it found it.
        onRun={(command, keys) =>
          bulk.run({ keys: [...keys], refresh, select() {} }, command)
        }
      />
    ),
    bulk: selection => (
      <BulkCommands
        selection={selection}
        commands={commands}
        bulk={bulk}
        onRun={(command, picked) => bulk.run(picked, command)}
      />
    ),
  };

  return (
    <DataWorkbench
      engine={engine}
      definitionId={EXECUTION_FAILED}
      {...HOST_LANGUAGE}
      record={{ actions, bulk }}
    />
  );
}

/** The workbench over the recorded compensation service. */
function Fixture() {
  const [fetcher] = useState(() =>
    compensationFetcher(RECORDED_COMPENSATION_HOST),
  );
  const [commands] = useState(() => compensationCommands(fetcher));
  return (
    <StoryEngine create={() => createCompensationEngine(fetcher)}>
      {engine => <ConsoleWorkbench engine={engine} commands={commands} />}
    </StoryEngine>
  );
}

const meta = {
  title: 'View Engine/回归夹具/补偿/记录工作台',
  component: Fixture,
  tags: ['!dev', '!autodocs', 'test'],
  parameters: {
    // Exercised as a host's screen: the workbench fills the page area
    // inside the host's bar and navigation (`AppShell`).
    layout: 'fullscreen',
  },
  beforeEach: installRecordedCompensationService,
  decorators: [
    Story => (
      <AppShell service={{ fixture: '录制的补偿服务 · 五次执行' }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Fixture>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The commands a row's menu offers, and which of them it will run. */
async function rowMenu(canvas: ReturnType<typeof within>, id: string) {
  await userEvent.click(canvas.getByRole('button', { name: `${id} 的操作` }));
  // The menu fades in, and until it is visible it takes no pointer: wait for
  // the one this press opened to be there to be pressed.
  const menu = await within(document.body).findByRole('menu');
  await waitFor(() => expect(menu).toBeVisible());
  const item = (name: string) => within(menu).getByRole('menuitem', { name });
  return { menu, item };
}

const enabled = (item: HTMLElement) =>
  item.getAttribute('aria-disabled') !== 'true' &&
  !item.hasAttribute('data-disabled');

/**
 * The system views listed, a row's commands opened by what its execution
 * takes, one run from the row with the bulk status line saying so, the
 * detail opened and closed from the keyboard, and an analysis view drawn.
 */
export const RowCommandsAndDetail: Story = {
  name: '行命令、结果条与详情',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    // The definition is admitted: every system view lists — the records and
    // the analyses in one list — and the first opens.
    for (const title of [
      '活动中',
      '不可重试',
      '不可恢复',
      '已成功',
      '全部',
      '按状态分布',
      '活动失败 · 按处理器',
      '每日新增失败',
    ])
      await expect(
        await canvas.findByRole('button', { name: new RegExp(`^${title}`) }),
      ).toBeVisible();
    await expect(
      canvas.getByRole('button', { name: /^活动中/ }),
    ).toHaveAttribute('aria-current', 'true');

    // Active executions, newest first, read through the nested snapshot paths.
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );
    await expect(readColumn(table, '状态')).toEqual([
      '已准备重试',
      '失败',
      '失败',
      '失败',
    ]);
    await expect(readTotal(table, '已重试次数').replace(/\D/g, '')).toBe('10');

    // A row offers what its execution takes: past the retry limit only the
    // forced retry, and never the recoverability it already has.
    const past = await rowMenu(canvas, 'EF-4');
    await expect(enabled(past.item('重试'))).toBe(false);
    await expect(enabled(past.item('强制重试'))).toBe(true);
    await expect(enabled(past.item('不可恢复'))).toBe(false);
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('menu')).toBeNull(),
    );

    // Retrying one from its row prepares it, says so, and the row shows it.
    const within1 = await rowMenu(canvas, 'EF-1');
    await userEvent.click(within1.item('重试'));
    // The workbench says what the command came to, above the rows.
    const settled = await waitFor(() => {
      const found = canvasElement.querySelector<HTMLElement>(
        '[data-slot="bulk-status"][data-state="settled"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(settled).toHaveTextContent('重试 · 1 项已完成');
    await waitFor(() =>
      expect(readColumn(table, '状态')).toEqual([
        '已准备重试',
        '失败',
        '失败',
        '已准备重试',
      ]),
    );

    // One execution read whole, from the keyboard: the rows are one Tab
    // stop, the arrows walk them, and Enter opens the panel beside the list
    // with what no column holds — the error the service recorded.
    const rowOf = (id: string) =>
      canvas.getByRole('button', { name: `${id} 的操作` }).closest('tr')!;
    rowOf('EF-5').focus();
    await userEvent.keyboard('{ArrowDown}');
    await waitFor(() => expect(rowOf('EF-4')).toHaveFocus());
    await userEvent.keyboard('{Enter}');
    const detail = await within(document.body).findByRole('dialog');
    await waitFor(() => expect(detail).toBeVisible());
    await expect(
      await within(detail).findByText('Inventory refused.'),
    ).toBeVisible();
    // Closed, the reader is back on the row they opened.
    await userEvent.keyboard('{Escape}');
    await waitFor(() =>
      expect(within(document.body).queryByRole('dialog')).toBeNull(),
    );
    await waitFor(() => expect(rowOf('EF-4')).toHaveFocus());

    // The analysis is a view of the same workbench, not another console.
    await userEvent.click(canvas.getByRole('button', { name: /^按状态分布/ }));
    await chartsDrawn(canvasElement);
    await waitFor(() => expect(drawnMarks(canvasElement)).toHaveLength(3));
  },
};

/**
 * A condition on a processor, picked from the values the service holds
 * rather than typed blind.
 *
 * `处理器` is text the definition lets the service group by value, so the
 * value box of its condition lists the processors the executions name, each
 * with how many executions failed in it — the service's own count, asked as a
 * `TERMS` aggregation (`POST …/snapshot/aggregation`) once the box opens.
 * Picking one and applying narrows the rows to it.
 */
export const ValuesFromTheData: Story = {
  name: '条件值取自数据',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );

    const toggle = canvas.getByRole('button', {
      name: new RegExp(`^${zhCN['label.filter.panel']}`),
    });
    if (toggle.getAttribute('aria-expanded') !== 'true')
      await userEvent.click(toggle);
    await userEvent.click(
      await canvas.findByRole('button', { name: zhCN['label.filter.add'] }),
    );
    const picker = await within(document.body).findByRole('dialog');
    await userEvent.click(
      within(picker).getByRole('checkbox', { name: '处理器' }),
    );
    await userEvent.click(
      within(picker).getByRole('button', {
        name: zhCN['label.filter.pick-done'],
      }),
    );
    await waitFor(() =>
      expect(document.body.querySelector('[role="dialog"]')).toBeNull(),
    );

    const box = await canvas.findByRole('combobox', {
      name: formatMessage(zhCN, 'label.filter.value-of', { field: '处理器' }),
    });
    await expect(box).toHaveAttribute(
      'placeholder',
      zhCN['label.filter.pick-or-type'],
    );
    await userEvent.click(box);
    // Every processor the executions name, the most frequent first, each with
    // its count — across the whole service, not only the active rows on
    // screen: what is offered is what the field can hold.
    const listbox = await within(document.body).findByRole('listbox');
    await waitFor(() =>
      expect(
        within(listbox)
          .getAllByRole('option')
          .map(option => option.getAttribute('aria-label')),
      ).toEqual([
        'OrderSaga（3 条记录）',
        'InventorySaga（1 条记录）',
        'PaymentSaga（1 条记录）',
      ]),
    );

    await userEvent.click(
      within(listbox).getByRole('option', { name: /^InventorySaga/ }),
    );
    await expect(box).toHaveValue('InventorySaga');
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.filter.apply'] }),
    );
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-4']));
  },
};

/**
 * 搜索错误常驻在标题栏：输入一段错误、按 Enter，只剩那几次执行；✕ 撤掉搜索、
 * 行回来。此前要打开条件、添加、勾「搜索错误」、完成、再输入，五步。
 */
export const SearchesTheErrors: Story = {
  name: '搜索错误',
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const table = await canvas.findByRole('table');
    await waitFor(() =>
      expect(readColumn(table, 'ID')).toEqual(['EF-2', 'EF-5', 'EF-4', 'EF-1']),
    );

    // Named by what it searches, and there without opening anything.
    const box = canvas.getByRole('searchbox', { name: '搜索错误' });
    await userEvent.type(box, 'gateway timed out');
    // Typing asks nothing yet: every key would be a query over the store.
    await expect(readColumn(table, 'ID')).toHaveLength(4);
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(readColumn(table, 'ID')).toEqual(['EF-2']));

    // The search is one of the conditions: the band says so.
    const applied = canvasElement.querySelector<HTMLElement>(
      '[data-slot="applied-bar"]',
    )!;
    await expect(applied).toHaveTextContent('gateway timed out');

    // ✕ takes it away and asks again.
    await userEvent.click(
      canvas.getByRole('button', { name: zhCN['label.search.clear'] }),
    );
    await waitFor(() => expect(readColumn(table, 'ID')).toHaveLength(4));
    await expect(box).toHaveValue('');
  },
};
