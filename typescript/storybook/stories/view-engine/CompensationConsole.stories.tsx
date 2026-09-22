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
import { useCallback, useRef, useState, type ReactNode } from 'react';
import { RecoverableType } from '@ahoo-wang/fetcher-wow';
import {
  systemInstanceId,
  type RecordKey,
  type ViewEngine,
} from '@ahoo-wang/fetcher-view-engine';
import {
  useBulkCommand,
  type BulkCommand,
  type BulkSelection,
  type RecordActionSlots,
} from '@ahoo-wang/fetcher-view-engine/react';
import {
  DataWorkbench,
  BulkOutcomeStrip,
  ViewSurface,
} from '@ahoo-wang/fetcher-view-engine/ui';
// View Engine's own primitives, so the added commands look like its own.
import { Button } from '@/ui/components/button';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/ui/components/dropdown-menu';
// Popup contents come themed from popups.tsx, as View Engine's own do.
import { DropdownMenuContent } from '@/ui/popups';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  DEFAULT_COMPENSATION_HOST,
  EXECUTION_FAILED,
  compensationCommands,
  compensationFetcher,
  createCompensationEngine,
  type CommandOutcome,
  type CompensationCommands,
} from './compensation.js';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

/**
 * View Engine against a real Wow compensation service: its failed executions,
 * at the volume a service really holds, and the commands that act on them.
 *
 * Nothing here is faked, so nothing here is repeatable: these stories are for
 * looking at, not for CI. The commands really write — point `host` at a test
 * environment.
 */

/** One command over a selection, named so the outcome can say which it was. */
interface ChosenCommand {
  title: string;
  run(id: string): Promise<CommandOutcome>;
}

const RECOVERABILITY: [RecoverableType, string][] = [
  [RecoverableType.RECOVERABLE, '可恢复'],
  [RecoverableType.UNRECOVERABLE, '不可恢复'],
  [RecoverableType.UNKNOWN, '未知'],
];

/**
 * The buttons, and nothing else. In-flight state, the outcome, the refresh
 * and what becomes of the selection are `useBulkCommand`'s — this used to be
 * 87 lines of them, written once here and once again in every other host.
 */
function CompensationActions({
  selection,
  commands,
  bulk,
  onRun,
}: {
  selection: BulkSelection;
  commands: CompensationCommands;
  bulk: BulkCommand;
  onRun(chosen: ChosenCommand, selection: BulkSelection): void;
}) {
  const count = selection.keys.length;
  const run = (
    title: string,
    command: (id: string) => Promise<CommandOutcome>,
  ) => onRun({ title, run: command }, selection);

  return (
    <>
      <Button
        size="sm"
        disabled={bulk.pending}
        onClick={() => run('重试', commands.retry)}
      >
        {count > 0 ? `重试 ${count} 条` : '重试'}
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={bulk.pending}
        onClick={() => run('强制重试', commands.forceRetry)}
      >
        强制重试
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button variant="outline" size="sm" disabled={bulk.pending} />
          }
        >
          标记可恢复性
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuGroup>
            {RECOVERABILITY.map(([value, label]) => (
              <DropdownMenuItem
                key={value}
                onClick={() =>
                  run(`标记为${label}`, id =>
                    commands.markRecoverable(id, value),
                  )
                }
              >
                {label}
              </DropdownMenuItem>
            ))}
          </DropdownMenuGroup>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

/**
 * The default workbench with the compensation commands hung in its bulk slot.
 *
 * The console adds no markup of its own: everything a business page needs —
 * what to do to a selection, and what came of it — reaches the workbench
 * through `actions`, which is the route an application takes when the default
 * look is right and only the commands are its own.
 */
function CompensationConsole({
  engine,
  commands,
}: {
  engine: ViewEngine;
  commands: CompensationCommands;
}) {
  // Which of the five was pressed. The hook holds one command, and these are
  // a menu of them, so the choice is made at the press and the wrapper below
  // stays the stable function the hook is given.
  const chosen = useRef<ChosenCommand | null>(null);
  const [title, setTitle] = useState<string>('');
  const bulk = useBulkCommand(
    useCallback(async (keys: readonly RecordKey[]) => {
      const outcomes = await Promise.all(
        keys.map(key => chosen.current!.run(String(key))),
      );
      const failed = outcomes.filter(found => found.error !== null);
      return {
        succeeded: outcomes
          .filter(found => found.error === null)
          .map(found => found.id),
        failed: failed.map(found => found.id),
        // One reason, not a list: the strip is a line, and the keys of every
        // record that failed are on the outcome for a host that wants more.
        reason: failed[0]?.error ?? undefined,
      };
    }, []),
  );

  const actions: RecordActionSlots = {
    bulk: selection => (
      <CompensationActions
        selection={selection}
        commands={commands}
        bulk={bulk}
        onRun={(command, picked) => {
          chosen.current = command;
          setTitle(command.title);
          bulk.run(picked);
        }}
      />
    ),
  };

  return (
    <div className="flex min-w-0 flex-col">
      {/* What the last command came to. It outlives the selection it acted
          on, so it sits beside the workbench rather than inside its toolbar. */}
      {bulk.outcome && (
        <ViewSurface {...HOST_LANGUAGE} className="px-3 pt-3">
          <BulkOutcomeStrip
            outcome={bulk.outcome}
            onDismiss={bulk.dismiss}
            title={title}
          />
        </ViewSurface>
      )}
      <DataWorkbench
        engine={engine}
        definitionId={EXECUTION_FAILED}
        {...HOST_LANGUAGE}
        record={{ actions: actions }}
      />
    </div>
  );
}

/**
 * One engine and one set of commands per host. Keyed by the host where it is
 * used, so pointing the Controls panel elsewhere starts over rather than
 * mixing two services' views.
 */
function Console({
  host,
  children,
}: {
  host: string;
  children: (engine: ViewEngine, commands: CompensationCommands) => ReactNode;
}) {
  const [fetcher] = useState(() => compensationFetcher(host));
  const [commands] = useState(() => compensationCommands(fetcher));
  return (
    <StoryEngine create={() => createCompensationEngine(fetcher)}>
      {engine => children(engine, commands)}
    </StoryEngine>
  );
}

function RecordConsole({ host }: { host: string }) {
  return (
    <Console key={host} host={host}>
      {(engine, commands) => (
        <CompensationConsole engine={engine} commands={commands} />
      )}
    </Console>
  );
}

function AnalysisConsole({ host }: { host: string }) {
  return (
    <Console key={host} host={host}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={EXECUTION_FAILED}
          instanceId={systemInstanceId(EXECUTION_FAILED, 'by-status')}
          {...HOST_LANGUAGE}
          kinds={['analysis']}
        />
      )}
    </Console>
  );
}

const scene = {
  domain: '真实后端',
  summary:
    'Wow 补偿服务里的执行失败：真实的数据、真实的数据量，操作会写回服务。',
  setup: '引擎与视图存储随故事新建；数据与补偿命令直连 host 指向的服务。',
  observe: '筛选、排序、分页与汇总都由服务端执行；命令等快照更新后再刷新结果。',
};

const meta = {
  title: 'View Engine/真实后端/补偿控制台',
  component: RecordConsole,
  // A live service answers differently every time, so this is never a
  // regression test, and its docs page lists the scenes rather than mounting
  // one: opening the catalog must not call the service.
  tags: ['!test'],
  parameters: { docs: { autoMount: false } },
  args: { host: DEFAULT_COMPENSATION_HOST },
  argTypes: {
    host: {
      control: 'text',
      description: 'Wow 补偿服务地址，改动后按新地址重建引擎。',
    },
  },
  decorators: [
    (Story, context) => (
      <ScenarioFrame
        title={context.name}
        {...scene}
        fixture={`Wow 补偿服务 · ${context.args.host}`}
      >
        <Story />
      </ScenarioFrame>
    ),
  ],
} satisfies Meta<typeof RecordConsole>;

export default meta;

type Story = StoryObj<typeof meta>;

/** The failed executions, their categories, and the commands on a selection. */
export const Records: Story = { name: '执行失败记录' };

/** Counts across the whole service, starting from the status breakdown. */
export const Analysis: Story = {
  name: '失败分析',
  render: args => <AnalysisConsole {...args} />,
};
