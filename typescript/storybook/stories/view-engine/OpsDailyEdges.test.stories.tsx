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
import type { StoryObj } from '@storybook/react-vite';
import { expect } from 'storybook/test';
import displayMeta, {
  DailyReport as DisplayOpsDaily,
} from './OpsDaily.stories.js';
import {
  expectDrawingKeepsGutter,
  expectTableBleeds,
  nextFrame,
  panelEdges,
} from './panelEdges.js';
import { noPanelOut } from './retail/twins.js';

/**
 * 运营日报's panels measured across (docs/design/ui/dashboard.md「表格贴到
 * 面板两边」), a twin of its own so the board's other twins stay as they are.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/运营日报/回归/表格贴边',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * A table runs to the panel's edges: the overdue list's checkboxes stand
 * under the title, its header band and its scrollbar reach the card's
 * edges, the held key stays there while the rows scroll — at every
 * density — while the charts keep the gutter.
 */
export const TableRunsToThePanelEdges: Story = {
  ...DisplayOpsDaily,
  name: '明细表贴到面板两边',
  play: async ({ canvasElement }) => {
    await noPanelOut(canvasElement);
    const gutters: number[] = [];
    for (const density of ['compact', 'default', 'comfortable']) {
      canvasElement.dataset.fveDensity = density;
      await nextFrame();
      gutters.push(panelEdges('GMV').gutter);
      await expectTableBleeds('付款超过 48 小时仍未发货', 'record-table');
      await expectDrawingKeepsGutter('今日与昨日的逐时 GMV', 'chart');
      await expectDrawingKeepsGutter('GMV', 'metric-card');
    }
    delete canvasElement.dataset.fveDensity;
    // The three steps really were three: 8, 12 and 16px.
    await expect(gutters.map(Math.round)).toEqual([8, 12, 16]);
  },
};
