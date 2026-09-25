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
import {
  DataWorkbench,
  EmbeddedDashboard,
} from '@ahoo-wang/wow-view-engine/ui';
import { AppShell } from '../shared/AppShell.js';
import {
  HOST_LANGUAGE,
  createStoryEngine,
  overviewDefinition,
  waybillsDefinition,
} from './fixtures.js';
import {
  LONG_TABLE_ROWS,
  manyWaybillsSource,
  salesBoard,
  salesDefinition,
  salesSource,
  salesView,
  waybillPageView,
  type LongTableRows,
} from './longTables.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

type Host = 'workbench' | 'board' | 'records';

/**
 * 一张长表：分析工作台里、仪表盘面板里，或一页两百条的记录表。
 */
function LongTableDemo({
  host = 'workbench',
  rows = 10_000,
}: {
  host?: Host;
  /** How many groups the analysis answers; the records page ignores it. */
  rows?: LongTableRows;
}) {
  if (host === 'records')
    return (
      <StoryEngine
        create={() =>
          createStoryEngine({
            definitions: [waybillsDefinition],
            source: manyWaybillsSource(),
            instances: [waybillPageView],
          })
        }
      >
        {engine => (
          <DataWorkbench
            engine={engine}
            definitionId={waybillsDefinition.id}
            instanceId={waybillPageView.id}
            {...HOST_LANGUAGE}
            kinds={['record']}
          />
        )}
      </StoryEngine>
    );
  const view = salesView(rows);
  return (
    <StoryEngine
      create={() =>
        createStoryEngine({
          definitions: [salesDefinition, overviewDefinition],
          source: salesSource(rows),
          instances: [view, salesBoard],
          // A host whose Wow server admits Wow's own ceiling (D42).
          limits: { maxAnalysisRows: 10_000 },
        })
      }
    >
      {engine =>
        host === 'board' ? (
          <EmbeddedDashboard
            engine={engine}
            instanceId={salesBoard.id}
            interaction="interactive"
            expandable
            {...HOST_LANGUAGE}
          />
        ) : (
          <DataWorkbench
            engine={engine}
            definitionId={salesDefinition.id}
            instanceId={view.id}
            {...HOST_LANGUAGE}
            kinds={['analysis']}
          />
        )
      }
    </StoryEngine>
  );
}

const FIXTURE = '内存 ViewStore · 一千到一万个客户的销售，一千条运单';

const description = `**长表**：分析结果最多 \`maxAnalysisRows\` 行——缺省一千（D42），宿主调高服务端的守卫后最多到 Wow 的上限一万，记录视图一页最多两百条（\`maxPageSize\`）。

- **多于一千组只画看得见的行**（D44）：一万行的表从结果到达到画完约 250ms，表头排序约 80ms（整张画时各约 1.1 秒）；滚动时画的永远是眼前那几十行。表格用 \`aria-rowcount\` 说自己有几行、每一行用 \`aria-rowindex\` 说自己是第几行。一千组以内照旧整张画出，页内查找与读屏的浏览模式都读得到每一行。
- **键盘**：结果里的行是一个 Tab 停靠点，↑／↓ 走行、Home／End 到两端——没画出来的那一行先滚到眼前再拿焦点。拿着停靠点的那一行一直画着，滚轮把它滚走焦点也不丢。
- **打印**：浏览器说要打印的那一刻画齐每一行。导出与复制读的是结果本身，从来不是屏上的行。
- **记录视图**一页最多两百条，整张画出。
- 回归故事在 CI 上守一万行表头排序的时间（\`SORT_GUARD_MS\`）与同时画出的行数。`;

const meta = {
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [
    Story => (
      <AppShell current="analysis" service={{ fixture: FIXTURE }}>
        <Story />
      </AppShell>
    ),
  ],
  title: 'View Engine/分析视图/长表',
  component: LongTableDemo,
  args: { host: 'workbench', rows: 10_000 },
  argTypes: {
    host: {
      control: 'inline-radio',
      options: ['workbench', 'board', 'records'],
    },
    rows: { control: 'inline-radio', options: [...LONG_TABLE_ROWS] },
  },
} satisfies Meta<typeof LongTableDemo>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * A thousand rows are drawn whole, and axe walking every one of them
 * outlasts the test; the longer tables draw a few dozen and are judged.
 */
const WHOLE_THOUSAND = { a11y: { test: 'off' } };

/** 一千个客户：门槛上，整张画出。 */
export const OneThousandRows: Story = {
  args: { rows: 1_000 },
  parameters: WHOLE_THOUSAND,
};

/** 五千个客户：只画看得见的行。 */
export const FiveThousandRows: Story = { args: { rows: 5_000 } };

/** 一万个客户：Wow 的上限，宿主把 `maxAnalysisRows` 调到头时一个结果最多的行数。 */
export const TenThousandRows: Story = { args: { rows: 10_000 } };

/** 同一张一万行的表，在仪表盘的面板里。 */
export const TenThousandRowsOnABoard: Story = {
  args: { host: 'board', rows: 10_000 },
};

/** 记录视图一页两百条：`maxPageSize`，二十列的宽表。 */
export const TwoHundredRecords: Story = { args: { host: 'records' } };
