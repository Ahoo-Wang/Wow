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
import { useState, type ReactNode } from 'react';
import { RecoverableType } from '@ahoo-wang/fetcher-wow';
import {
  systemInstanceId,
  type ViewEngine,
} from '@ahoo-wang/fetcher-view-engine';
import {
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useSaveCommands,
  useViewList,
  useViewRuntime,
  type RecordTableController,
} from '@ahoo-wang/fetcher-view-engine/react';
import {
  AnalysisWorkbench,
  FilterPanel,
  RecordCards,
  RecordTable,
  RecordToolbar,
  SaveActions,
  ViewList,
  ViewSurface,
  useViewMessages,
} from '@ahoo-wang/fetcher-view-engine/ui';
// View Engine's own primitives, so the added commands look like its toolbar.
import { Alert, AlertDescription, AlertTitle } from '@/ui/components/alert';
import { Button } from '@/ui/components/button';
import {
  DropdownMenu,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/ui/components/dropdown-menu';
// Popup contents come themed from popups.tsx, as View Engine's own do.
import { DropdownMenuContent } from '@/ui/popups';
import { Separator } from '@/ui/components/separator';
import { Skeleton } from '@/ui/components/skeleton';
import { ScenarioFrame } from '../shared/ScenarioFrame.js';
import {
  DEFAULT_COMPENSATION_HOST,
  EXECUTION_FAILED,
  EXECUTION_FAILED_ANALYSIS,
  compensationCommands,
  compensationFetcher,
  createCompensationEngine,
  type CommandOutcome,
  type CompensationCommands,
} from './compensation.js';
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

interface Outcome {
  title: string;
  outcomes: CommandOutcome[];
}

const RECOVERABILITY: [RecoverableType, string][] = [
  [RecoverableType.RECOVERABLE, '可恢复'],
  [RecoverableType.UNRECOVERABLE, '不可恢复'],
  [RecoverableType.UNKNOWN, '未知'],
];

/**
 * One command over every selected row, then a refresh, so the table shows what
 * the service now holds. Each command waits for the snapshot, so the refresh
 * reads its effect rather than racing it.
 */
function CompensationActions({
  table,
  commands,
  onOutcome,
}: {
  table: RecordTableController;
  commands: CompensationCommands;
  onOutcome(outcome: Outcome): void;
}) {
  const [running, setRunning] = useState(false);
  const ids = table.rows
    .filter(row => table.isSelected(row.key))
    .map(row => String(row.key));
  const disabled = running || ids.length === 0;

  const run = async (
    title: string,
    command: (id: string) => Promise<CommandOutcome>,
  ) => {
    setRunning(true);
    const outcomes = await Promise.all(ids.map(command));
    setRunning(false);
    onOutcome({ title, outcomes });
    table.refresh();
  };

  return (
    <>
      <Button
        size="sm"
        disabled={disabled}
        onClick={() => void run('重试', commands.retry)}
      >
        {ids.length > 0 ? `重试 ${ids.length} 条` : '重试'}
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled}
        onClick={() => void run('强制重试', commands.forceRetry)}
      >
        强制重试
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={<Button variant="outline" size="sm" disabled={disabled} />}
        >
          标记可恢复性
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuGroup>
            {RECOVERABILITY.map(([value, label]) => (
              <DropdownMenuItem
                key={value}
                onClick={() =>
                  void run(`标记为${label}`, id =>
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

function OutcomeAlert({ outcome }: { outcome: Outcome }) {
  const failed = outcome.outcomes.filter(found => found.error !== null);
  const succeeded = outcome.outcomes.length - failed.length;
  return (
    <Alert variant={failed.length > 0 ? 'destructive' : 'default'}>
      <AlertTitle>
        {`${outcome.title}：${succeeded} 条成功`}
        {failed.length > 0 && `，${failed.length} 条失败`}
      </AlertTitle>
      {failed.length > 0 && (
        <AlertDescription>
          {failed.slice(0, 5).map(found => (
            <p key={found.id}>{`${found.id}：${found.error}`}</p>
          ))}
        </AlertDescription>
      )}
    </Alert>
  );
}

/**
 * `RecordWorkbench` composed again from the same hooks, with the compensation
 * commands in its toolbar. This is the route the workbench documents for an
 * application that needs more than the default: nothing here is private.
 */
function CompensationWorkbench({
  engine,
  commands,
}: {
  engine: ViewEngine;
  commands: CompensationCommands;
}) {
  const list = useViewList(engine, EXECUTION_FAILED);
  const [chosen, setChosen] = useState<string | null>(null);
  const openId = chosen ?? list.defaultInstanceId;

  const opened = useOpenView(engine, openId);
  const runtime = opened.runtime;
  const state = useViewRuntime(runtime);
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record);
  const filter = useFilterEditor(runtime);
  const save = useSaveCommands(engine, runtime);
  const messages = useViewMessages();
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const errors = (state?.issues ?? []).filter(
    found => found.severity === 'error',
  );

  return (
    <ViewSurface className="gap-0 md:flex-row">
      <aside className="flex w-56 shrink-0 flex-col gap-2 p-3">
        <ViewList
          list={list}
          currentId={state?.saved?.id ?? null}
          onOpen={id => {
            setOutcome(null);
            setChosen(id);
          }}
        />
      </aside>

      <Separator orientation="vertical" className="hidden md:block" />

      <main className="flex min-w-0 flex-1 flex-col gap-3 p-3">
        {opened.error && (
          <Alert variant="destructive">
            <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
            <AlertDescription>{messages.issue(opened.error)}</AlertDescription>
          </Alert>
        )}

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {runtime && (
          <>
            <FilterPanel filter={filter} disabled={table.loading} />

            {errors.length > 0 && (
              <Alert variant="destructive">
                <AlertTitle>
                  {messages.label('label.view.needs-fixing')}
                </AlertTitle>
                <AlertDescription>{messages.issues(errors)}</AlertDescription>
              </Alert>
            )}

            <RecordToolbar table={table} fields={runtime.fields}>
              <CompensationActions
                table={table}
                commands={commands}
                onOutcome={setOutcome}
              />
              <SaveActions
                commands={save}
                title={state?.title ?? ''}
                onSaved={saved => {
                  setChosen(saved.id);
                  list.reload();
                }}
                onRenamed={instance => {
                  setChosen(instance.id);
                  list.reload();
                }}
                onDeleted={() => setChosen(null)}
                onRecovered={() => list.reload()}
              />
            </RecordToolbar>

            {outcome && <OutcomeAlert outcome={outcome} />}

            {table.error && (
              <Alert variant="destructive">
                <AlertTitle>{messages.label('label.query.failed')}</AlertTitle>
                <AlertDescription>
                  {messages.issue(table.error)}
                </AlertDescription>
              </Alert>
            )}

            {table.layout === 'card' ? (
              <RecordCards table={table} />
            ) : (
              <RecordTable table={table} />
            )}
          </>
        )}
      </main>
    </ViewSurface>
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
        <CompensationWorkbench engine={engine} commands={commands} />
      )}
    </Console>
  );
}

function AnalysisConsole({ host }: { host: string }) {
  return (
    <Console key={host} host={host}>
      {engine => (
        <AnalysisWorkbench
          engine={engine}
          definitionId={EXECUTION_FAILED_ANALYSIS}
          instanceId={systemInstanceId(EXECUTION_FAILED_ANALYSIS, 'by-status')}
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
