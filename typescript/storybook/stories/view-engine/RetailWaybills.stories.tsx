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
import { RETAIL_WAYBILLS, retailWaybillsDefinition } from './retail/views.js';
import '@ahoo-wang/wow-view-engine/styles.css';

function WaybillScene() {
  return (
    <StoryEngine create={() => createRetailEngine([retailWaybillsDefinition])}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId={RETAIL_WAYBILLS}
          {...HOST_LANGUAGE}
          record={{ selectable: false, emptyTitle: '没有符合条件的包裹' }}
        />
      )}
    </StoryEngine>
  );
}

const description = `**业务场景 · 运单宽表**

物流运营对账、查时效用的宽表：一包一行，20 列横着看——承运商、仓库、目的地、件数、重量、发出与签收、时效。

${RETAIL_DATA_NOTE}

- **数据**：运单是子订单 \`packages\` 的展开（约 1.9 万个包裹），和订单同源，不会对不上。
- **系统视图**：运单宽表（20 列，运单号冻结在左）、在途包裹（最早发出的在前）、台风期间：两广中通、两广：承运商 × 周的签收时长（热力图，A-13）。
- **看什么**：「两广：承运商 × 周的签收时长」——6 月以来每周每家承运商在广东、广西的平均签收小时数，**中通 7 月 20 日那一周约 119 小时**，平时 50 多（埋下的异常 A2，台风）；点那一格「查看这些记录」，或打开「台风期间：两广中通」看那 12 个包裹。
- **还能做**：横向滚动时表头与左侧的运单号不动；两行汇总（件数、重量合计，平均签收时长）；拖列宽、调列序、冻结列；导出 CSV。`;

const meta = {
  title: 'View Engine/业务场景/运单宽表',
  component: WaybillScene,
  parameters: {
    layout: 'fullscreen',
    docs: { description: { component: description } },
  },
  decorators: [retailShell('retail-waybills')],
} satisfies Meta<typeof WaybillScene>;

export default meta;

type Story = StoryObj<typeof meta>;

export const WaybillWideTable: Story = { name: '运单宽表' };
