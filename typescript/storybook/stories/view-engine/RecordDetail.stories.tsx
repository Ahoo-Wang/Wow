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
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import {
  systemInstanceId,
  type RecordKey,
  type ViewEngine,
  type ViewSource,
} from '@ahoo-wang/wow-view-engine';
import type {
  RecordDetailSection,
  RecordDetailSectionContext,
} from '@ahoo-wang/wow-view-engine/react';
import { DataWorkbench, EmbeddedView } from '@ahoo-wang/wow-view-engine/ui';
// View Engine's own button, as the host's commands in the other record
// stories: a host section is the host's markup inside the engine's panel.
import { Button } from '@/ui/components/button';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  savedViews,
  storySource,
} from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * How the source answers a read of **one** record — the detail's own query
 * (`runtime.fetchRecord`, a page of one); the list's queries always answer.
 *
 * - `data`: the record, or nothing when there is no such order;
 * - `slow`: the same, a second and a half later;
 * - `forbidden`: an HTTP 403, the way a gateway refuses a record of
 *   someone else's tenant;
 * - `failing-once`: a failure the first time, the record on the next try.
 */
export type DetailBehaviour = 'data' | 'slow' | 'forbidden' | 'failing-once';

function detailSource(behaviour: DetailBehaviour): ViewSource {
  const source = storySource('data');
  let failed = false;
  return {
    ...source,
    paged: async query => {
      if (query.pagination?.size !== 1) return source.paged(query);
      if (behaviour === 'slow') await delay(1_500);
      if (behaviour === 'forbidden')
        // The shape fetcher's `ExchangeError` has, which is all the engine
        // reads: a response whose status is the refusal.
        throw Object.assign(new Error('Forbidden'), {
          exchange: { response: { status: 403 } },
        });
      if (behaviour === 'failing-once' && !failed) {
        failed = true;
        throw new Error('连接被重置');
      }
      return source.paged(query);
    },
  };
}

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * The host's two sections, as an operations console writes them: what can
 * be done about the record, first — a form of the host's with its own
 * command — and, after the engine's fields, the same warehouse's other
 * orders as an `EmbeddedView`, which reads only once the record is opened.
 */
function hostSections(
  engine: ViewEngine,
  { row, refresh }: RecordDetailSectionContext,
): RecordDetailSection[] {
  return [
    {
      id: 'handling',
      title: '处理',
      placement: 'start',
      render: () => <Handling id={String(row.key)} refresh={refresh} />,
    },
    {
      id: 'warehouse-orders',
      title: '同仓订单',
      render: () => (
        <EmbeddedView
          engine={engine}
          instanceId={systemInstanceId('orders', 'all')}
          scopeFilter={{
            op: 'and',
            children: [
              {
                field: 'warehouse',
                operator: 'IN',
                value: [String(row.data.warehouse)],
              },
            ],
          }}
          messages={HOST_LANGUAGE.messages}
          locale={HOST_LANGUAGE.locale}
          headingLevel={4}
        />
      ),
    },
  ];
}

/** A host's own form: a note and a command, in the engine's buttons. */
function Handling({ id, refresh }: { id: string; refresh(): void }) {
  const [reviewed, setReviewed] = useState(false);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <p className="text-muted-foreground">
        {reviewed ? `${id} 已复核。` : `${id} 还没有复核。`}
      </p>
      <Button
        variant="outline"
        size="sm"
        disabled={reviewed}
        onClick={() => {
          setReviewed(true);
          refresh();
        }}
      >
        标记为已复核
      </Button>
    </div>
  );
}

function RecordDetailDemo({
  open: linked = null,
  behaviour = 'data',
  sections = true,
}: {
  /**
   * The record the host's address names (`?id=`), or none. Held by the host
   * from then on: a press on a row, the detail's close and the link below
   * the workbench all go through it.
   */
  open?: string | null;
  behaviour?: DetailBehaviour;
  /** Whether the host adds its two sections. */
  sections?: boolean;
}) {
  const [id, setId] = useState<RecordKey | null>(linked);
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          source: detailSource(behaviour),
          instances: savedViews,
        })
      }
    >
      {engine => (
        <div
          data-host-address={id === null ? '' : `?id=${String(id)}`}
          style={{ display: 'contents' }}
        >
          <DataWorkbench
            engine={engine}
            definitionId="orders"
            instanceId={savedViews[0].id}
            messages={HOST_LANGUAGE.messages}
            locale={HOST_LANGUAGE.locale}
            record={{
              detail: {
                open: id,
                onOpenChange: setId,
                ...(sections
                  ? { sections: context => hostSections(engine, context) }
                  : {}),
              },
            }}
          />
        </div>
      )}
    </StoryEngine>
  );
}

/** What the scenes answer from, said in the host's service line. */
const FIXTURE = '内存 ViewStore · 七条订单 · 可切换的单条读取';

const description = `**数据视图 · 记录详情：宿主节与地址里的那一条（G2）**

宿主往记录详情里加自己的节，并由自己握着开着的是哪一条——地址里的 \`?id=\`。

- **数据源**：${FIXTURE}。列表是「待出库订单」；SO-1002 已取消，不在这一页上。
- **操作**：按一行（或在行上按 Enter）打开详情；关掉它；或打开一个带着 \`open\` 的故事，像从告警里的链接进来。
- **观察**：「处理」在最前，「同仓订单」（一个 \`EmbeddedView\`，打开记录时才去读）在引擎的字段之后，两节都有标题、是有名字的区域；不在这一页上的那条单独读出来，读的时候有骨架，不在了、没有权限、读不到（可重试）各说各的；关掉后焦点回到那一行。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="records" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/组件状态/记录工作台/记录详情',
  component: RecordDetailDemo,
  args: { behaviour: 'data', sections: true },
  argTypes: {
    behaviour: {
      control: 'inline-radio',
      options: ['data', 'slow', 'forbidden', 'failing-once'],
    },
    open: { control: 'text' },
  },
} satisfies Meta<typeof RecordDetailDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Press a row: the host's two sections sit among the engine's. */
export const HostSections: Story = {};

/** Opened from a link to an order on the page. */
export const LinkedOnPage: Story = { args: { open: 'SO-1003' } };

/** Opened from a link to an order the page does not hold. */
export const LinkedOffPage: Story = { args: { open: 'SO-1002' } };

/** The same link, while the one record is still being read. */
export const LinkedReading: Story = {
  args: { open: 'SO-1002', behaviour: 'slow' },
};

/** A link to an order that is not there. */
export const LinkedNotThere: Story = { args: { open: 'SO-9999' } };

/** A link to an order the source refuses this reader. */
export const LinkedForbidden: Story = {
  args: { open: 'SO-1002', behaviour: 'forbidden' },
};

/** A link whose read fails once; 「重试」 reads it. */
export const LinkedFailed: Story = {
  args: { open: 'SO-1002', behaviour: 'failing-once' },
};
