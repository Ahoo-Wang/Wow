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
import { expect, userEvent, waitFor, within } from 'storybook/test';
import { zhCN } from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  NoMapRegistered as DisplayNoMap,
  WorldMap as DisplayWorldMap,
} from './AnalysisMap.stories.js';
import { formatRgb, parse } from 'culori';
import { chartsDrawn, pressMark } from './chartDom.js';

/**
 * 地图的轻量孪生（D41）：宿主注册的世界地图画得出、地图上没有的国家说出来、
 * 按下一个国家弹出追问菜单；没有注册地图时，图说没有、图型磁贴置灰。
 */
const meta = {
  ...displayMeta,
  title: 'View Engine/能力/地图/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { ...displayMeta.parameters },
};

export default meta;

type Story = StoryObj<typeof displayMeta>;

const mapFrame = (canvas: HTMLElement) =>
  canvas.querySelector<HTMLElement>('[data-slot="chart"][data-chart="map"]');

const readingOf = (canvas: HTMLElement) =>
  [
    ...(canvas.querySelector<HTMLTableElement>(
      '[data-slot="chart-reading"] table',
    )?.tBodies[0]?.rows ?? []),
  ].map(row => [...row.cells].map(cell => cell.textContent ?? ''));

/** 16 个国家在地图上，新加坡不在；美国最深，按下它追问。 */
export const WorldMapDrawn: Story = {
  ...DisplayWorldMap,
  play: async ({ canvasElement }) => {
    const frame = await waitFor(
      () => {
        const found = mapFrame(canvasElement);
        expect(found).toHaveAttribute('data-map', 'ready');
        return found!;
      },
      { timeout: 10_000 },
    );
    await chartsDrawn(canvasElement);
    await expect(frame).toHaveAttribute('data-marks', '16');
    const rows = readingOf(canvasElement);
    await expect(rows).toHaveLength(17);
    await expect(rows[0]?.[0]).toBe('United States of America');
    await expect(
      frame.querySelector('[data-slot="map-notes"]'),
    ).toHaveTextContent(
      zhCN['label.chart.map.unplaced'].replace('{count}', '1'),
    );
    // The deepest area is the largest market.
    const fills = [
      ...frame.querySelectorAll<SVGPathElement>(
        '[data-slot="chart-plot"] svg path',
      ),
    ];
    const rgb = (text: string | null) =>
      text && parse(text) ? formatRgb(parse(text)!) : '';
    const first = rgb(getComputedStyle(frame).getPropertyValue('--chart-1'));
    const deepest = fills.find(
      path => rgb(path.getAttribute('fill')) === first,
    );
    await expect(deepest).toBeDefined();
    pressMark(deepest!);
    const menu = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="drill-menu"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(menu).toHaveTextContent('United States of America');
    await userEvent.keyboard('{Escape}');
  },
};

/** 没有注册地图：图说没有，可视化面板里地图置灰并写原因。 */
export const NoMapSaid: Story = {
  ...DisplayNoMap,
  play: async ({ canvasElement }) => {
    const frame = await waitFor(() => {
      const found = mapFrame(canvasElement);
      expect(found).toHaveAttribute('data-map', 'missing');
      return found!;
    });
    await expect(
      frame.querySelector('[data-slot="map-notes"]'),
    ).toHaveTextContent(zhCN['label.chart.map.missing']);
    await userEvent.click(
      within(canvasElement).getByRole('button', {
        name: zhCN['label.analysis.visualize'],
      }),
    );
    const tile = await waitFor(() => {
      const found = document.querySelector<HTMLElement>(
        '[data-slot="chart-tile"][data-chart-type="map"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    await expect(tile).toHaveAttribute('aria-disabled', 'true');
    await expect(tile).toHaveTextContent(zhCN['chart.fit.needs-map']);
  },
};
