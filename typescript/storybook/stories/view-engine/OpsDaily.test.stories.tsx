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
import { expect, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  DailyReport as DisplayOpsDaily,
} from './OpsDaily.stories.js';
import { DAILY_GOLDEN } from './retail/goldens.js';
import { noPanelOut, valueOf } from './retail/twins.js';

/**
 * 运营日报 in the dashboard workbench, as a lightweight twin (docs/scenarios.md 6.1): it draws without a
 * panel refusing its view, and shows what 4.1 says it shows.
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/业务场景/运营日报/回归',
  tags: ['!dev', '!autodocs', 'test'],
  // Spelled out, not left to the spread (see the README).
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

/**
 * 运营日报 in the workbench: the same golden cards as the home page, and —
 * unlike the home page — 「编辑」, since this is where the team builds it.
 */
export const OpsDailyInTheWorkbench: Story = {
  ...DisplayOpsDaily,
  name: '运营日报',
  play: async ({ canvasElement }) => {
    await waitFor(() => expect(valueOf('GMV')).toBe(DAILY_GOLDEN.cards.GMV), {
      timeout: 10_000,
    });
    await noPanelOut(canvasElement);
    await expect(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.dashboard.edit'],
      }),
    ).toBeInTheDocument();
  },
};
