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
import { DataWorkbench } from '@ahoo-wang/wow-view-engine/ui';
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
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={RETAIL_AFTER_SALES}
          {...HOST_LANGUAGE}
          record={{
            // Nothing here acts on a selection: an after-sale case is
            // audited in its own application.
            selectable: false,
            emptyTitle: '没有符合条件的售后单',
          }}
        />
      )}
    </StoryEngine>
  );
}

const description = `**业务场景 · 售后工作台**

售后与财务的工作台：待处理的售后单、本月售后对账，以及退的是什么、为什么退。

${RETAIL_DATA_NOTE}

- **系统视图**：全部售后、待处理（待审核与待退款，最早申请的在前）、售后理由构成（环图，A-17）、各类目的售后理由（百分比堆叠横条）、每日退款金额（近 30 天，柱加本期累计）。
- **共享与个人视图**：财务的「本月售后」（导出 CSV 对账）；品控的「竹纤维浴巾的质量投诉」与「竹纤维浴巾：每月售后理由」。
- **看什么**：「竹纤维浴巾：每月售后理由」——5 月之前质量问题一年只有一两单，**5 月 10 日之后每月十几二十单质量问题**（埋下的异常 A1）；「各类目的售后理由」里床品布艺的质量问题因此压过了尺寸或颜色不符。从分析工作台「退款率最高的商品」追下来，就是这里。
- **还能做**：在「本月售后」上导出 CSV（本页或全部）；按售后理由、渠道筛；卡片布局；点一行看全部字段。`;

const meta = {
  title: 'View Engine/业务场景/售后工作台',
  component: AfterSaleScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [retailShell('retail-after-sales')],
} satisfies Meta<typeof AfterSaleScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const AfterSaleWorkbench: Story = { name: '售后工作台' };
