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
import { RecoverableType } from '@ahoo-wang/fetcher-wow';
import type {
  RecordKey,
  RecordRow,
  ViewEngine,
} from '@ahoo-wang/fetcher-view-engine';
import {
  useBulkCommand,
  type BulkCommand,
  type BulkRun,
  type BulkSelection,
  type RecordActionSlots,
} from '@ahoo-wang/fetcher-view-engine/react';
import { DataWorkbench } from '@ahoo-wang/fetcher-view-engine/ui';
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
  DEFAULT_COMPENSATION_HOST,
  EXECUTION_FAILED,
  compensationCommands,
  compensationFetcher,
  createCompensationEngine,
  type CompensationCommands,
} from './compensation.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * The compensation console — a data console, one workbench for the records
 * and the analysis over them — against a real Wow compensation service: its failed executions
 * at the volume a service really holds, the analysis over them, and the
 * commands that act on them — one workbench, as an operator uses it. The
 * record views and the analysis views sit in one list; a row carries its own
 * commands and a selection carries them in bulk.
 *
 * Nothing here is faked, so nothing here is repeatable: this story is for
 * using, not for CI (its regression twin runs over a recorded service). The
 * commands really write — point `host` at a test environment.
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
 * The console adds no markup of its own besides the commands: everything a
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

/**
 * One engine and one set of commands per host, keyed by the host so pointing
 * the Controls panel elsewhere starts over rather than mixing two services'
 * views.
 */
function Console({ host }: { host: string }) {
  return <HostConsole key={host} host={host} />;
}

function HostConsole({ host }: { host: string }) {
  const [fetcher] = useState(() => compensationFetcher(host));
  const [commands] = useState(() => compensationCommands(fetcher));
  return (
    <StoryEngine create={() => createCompensationEngine(fetcher)}>
      {engine => <ConsoleWorkbench engine={engine} commands={commands} />}
    </StoryEngine>
  );
}

/**
 * What the scene is, on the docs page rather than above the console: the
 * console is the product an operator uses, so it has the screen to itself.
 */
const description = `**真实后端 · 补偿控制台**

Wow 补偿服务里的执行失败：真实的数据、真实的数据量；同一个工作台里查明细、做分析、逐条或成批地重试与标记。

- **数据源**：Wow 补偿服务，地址是 \`host\` 参数，可在 Controls 面板切换；改动后按新地址重建引擎。
- **准备**：引擎与视图存储随故事新建；数据与补偿命令直连 \`host\` 指向的服务。
- **操作**：打开「快照控制台」场景，它放在宿主应用里——顶部导航与左侧应用导航是宿主的，中间那一块是视图引擎——像操作员使用时一样。
- **观察**：筛选、排序、分页、汇总与聚合都由服务端执行；每行的操作按这条执行的状态开放，命令等快照更新后再刷新结果。

> 命令会真实写回服务：重试、强制重试与标记可恢复性都作用在 \`host\` 指向的服务上，只连接测试环境。`;

const meta = {
  title: 'View Engine/真实后端/补偿控制台/快照控制台',
  component: Console,
  // A live service answers differently every time, so this is never a
  // regression test, and its docs page does not mount it: opening the
  // catalog must not call the service.
  tags: ['!test'],
  parameters: {
    // The console is the product: it fills the canvas inside the host's
    // own bar and navigation (`AppShell`), as it would a screen.
    layout: 'fullscreen',
    docs: { autoMount: false, description: { component: description } },
  },
  args: { host: DEFAULT_COMPENSATION_HOST },
  argTypes: {
    host: {
      control: 'text',
      description: 'Wow 补偿服务地址，改动后按新地址重建引擎。',
    },
  },
  decorators: [
    (Story, context) => (
      <AppShell current="snapshots" service={{ host: context.args.host }}>
        <Story />
      </AppShell>
    ),
  ],
} satisfies Meta<typeof Console>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Failed executions, the analysis over them, and the commands on either. */
export const DataConsole: Story = { name: '快照控制台' };
