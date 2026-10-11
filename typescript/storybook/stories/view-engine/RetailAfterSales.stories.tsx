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
  actions,
  type RecordRow,
  type ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import { bind, DataWorkbench, ViewHost } from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import { RETAIL_DATA_NOTE, retailShell } from './retail/scene.js';
import { createRetailEngine } from './retail/source.js';
import {
  AFTER_SALE_WORKBENCH_VIEWS,
  RETAIL_AFTER_SALES,
  retailAfterSalesDefinition,
} from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';
import sceneSource from './RetailAfterSales.stories.tsx?raw';
import { hostSource } from './hostSource.js';

/** The statuses a case is still waiting on someone in. */
const IN_PROGRESS = new Set(['REQUESTED', 'APPROVED']);

/** Why a case cannot be chased, by where it stands (R2-100). */
const SETTLED: Record<string, string> = {
  REJECTED: '这张售后单已经驳回，不用催',
  REFUNDED: '这张售后单已经退款，不用催',
  EXCHANGED: '这张售后单已经换货，不用催',
};

function chaseable(row: RecordRow): true | string {
  const state = (row.data.state ?? {}) as Record<string, unknown>;
  const status = String(state.status);
  if (IN_PROGRESS.has(status)) return true;
  return SETTLED[status] ?? '这张售后单已经处理完，不用催';
}

/**
 * The host's one command here (host-integration.md 5): 「催处理」, a reminder
 * pushed to the after-sales team's queue for a case still waiting — on its
 * review or its refund. Like the orders' 「催发货」 it changes nothing on the
 * case itself, which is audited and settled in its own application; a
 * settled case is refused with the reason before anything is sent. Before
 * this the scene had no action at all (R2-100).
 */
const AFTER_SALE_ACTIONS = actions([
  {
    id: 'chase',
    label: '催处理',
    primary: true,
    available: chaseable,
    run: () => new Promise(resolve => setTimeout(resolve, 120)),
  },
]);

const AFTER_SALE_BINDINGS = [
  bind(RETAIL_AFTER_SALES, { actions: AFTER_SALE_ACTIONS }),
];

/**
 * One workbench for both kinds of view, as the list marks each with its
 * kind's icon: the scene is the walk from 「为什么退」 down to the cases
 * themselves, and splitting it would cut that walk in two.
 */
function AfterSaleHost({ engine }: { engine: ViewEngine }) {
  return (
    <ViewHost
      engine={engine}
      {...HOST_LANGUAGE}
      bindings={AFTER_SALE_BINDINGS}
      colorMode="host"
    >
      <DataWorkbench
        definitionId={RETAIL_AFTER_SALES}
        record={{ emptyTitle: '没有符合条件的售后单' }}
      />
    </ViewHost>
  );
}

function AfterSaleScene() {
  return (
    <StoryEngine
      create={() =>
        createRetailEngine(
          [retailAfterSalesDefinition],
          AFTER_SALE_WORKBENCH_VIEWS,
        )
      }
    >
      {engine => <AfterSaleHost engine={engine} />}
    </StoryEngine>
  );
}

const description = `**业务场景 · 售后工作台**

售后与财务的工作台：待处理的售后单、本月售后对账，以及退的是什么、为什么退。

${RETAIL_DATA_NOTE}

- **系统视图**：全部售后、待处理（待审核与待退款，最早申请的在前）、售后理由构成（环图，A-17）、各类目的售后理由（百分比堆叠横条）、每日退款金额（近 30 天，柱加本期累计）。
- **共享与个人视图**：财务的「本月售后」（导出 CSV 对账）；品控的「竹纤维浴巾的质量投诉」与「竹纤维浴巾：每月售后理由」。
- **看什么**：「竹纤维浴巾：每月售后理由」——5 月之前质量问题寥寥无几，**5 月 10 日之后每月都有成批的质量问题**（埋下的异常 A1）；「各类目的售后理由」里床品布艺的质量问题因此压过了尺寸或颜色不符。从分析工作台「退款率最高的商品」追下来，就是这里。
- **还能做**：在「待处理」里勾几张按「催处理」，结果条逐条说成了几单；已驳回、已退款或已换货的单会被拒，并按它的状态说明原因。在「本月售后」上导出 CSV（本页或全部）；按售后理由、渠道筛；卡片布局；点一行看全部字段。`;

/** What 「Show code」 shows on this page (`hostSource.ts`). */
const HOST_CODE = hostSource(['RetailAfterSales.stories.tsx', sceneSource]);

const meta = {
  title: 'View Engine/业务场景/售后工作台',
  component: AfterSaleScene,
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: { component: description },
      // 「Show code」: the host's side of this scene, read from the file.
      source: { code: HOST_CODE, language: 'tsx' },
    },
  },
  decorators: [retailShell('retail-after-sales')],
} satisfies Meta<typeof AfterSaleScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const AfterSaleWorkbench: Story = { name: '售后工作台' };
