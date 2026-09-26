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
import {
  formatMessage,
  registerChartMap,
  zhCN,
  type ChartMapGeoJson,
} from '@ahoo-wang/wow-view-engine/ui';
import displayMeta, {
  NoMapRegistered as DisplayNoMap,
  WorldMap as DisplayWorldMap,
} from './AnalysisMap.stories.js';
import { differenceEuclidean, formatRgb, parse } from 'culori';
import { chartsDrawn, pressMark } from './chartDom.js';
import { CHINA_MAP } from './chinaProvinces.js';
import { amountOf, findDataTable } from './readTable.js';

/**
 * 地图的轻量孪生（D41）：宿主注册的世界地图画得出、地图上没有的国家说出来、
 * 按下一个国家弹出追问菜单；没有注册地图时，图说没有、图型磁贴置灰。
 *
 * 中国省级地图的展示故事从 DataV 在线读取边界，回归不连外网：这里注册一张
 * 合成的小地图（一排以省名命名的方块），量同一个分析——按 GMV 上色、色标、
 * 追问菜单的「查看这些记录」与「按其他维度细分…」、没有地图时的样子。
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

/**
 * 合成的「中国」：一排六个方块，从左到右是五个 GMV 最高的省与台湾省（在
 * 地图上、不在数据里）。只有名字是真的，形状与位置都不是。
 */
const FIXTURE_PROVINCES = [
  '广东省',
  '浙江省',
  '江苏省',
  '山东省',
  '湖南省',
  '台湾省',
] as const;

const FIXTURE_MAP: ChartMapGeoJson = {
  type: 'FeatureCollection',
  features: FIXTURE_PROVINCES.map((name, at) => {
    const west = 100 + at * 3;
    return {
      type: 'Feature',
      properties: { name },
      geometry: {
        type: 'Polygon',
        coordinates: [
          [
            [west, 30],
            [west + 2, 30],
            [west + 2, 32],
            [west, 32],
            [west, 30],
          ],
        ],
      },
    };
  }),
};

/** 注册合成的小地图，名字与 DataV 那张相同，故事结束时取回。 */
const registerFixtureChina = () =>
  registerChartMap({
    name: CHINA_MAP,
    label: '中国（合成的回归夹具）',
    load: async () => FIXTURE_MAP,
  });

/** 地图画好了：地理数据到了、图库画完了。 */
async function mapReady(canvasElement: HTMLElement): Promise<HTMLElement> {
  const frame = await waitFor(
    () => {
      const found = mapFrame(canvasElement);
      expect(found).toHaveAttribute('data-map', 'ready');
      return found!;
    },
    { timeout: 10_000 },
  );
  await chartsDrawn(canvasElement);
  return frame;
}

/**
 * 六个方块，从左到右：图库画的面里一排的那几块（色标的渐变条填的是渐变，
 * 不算）。
 */
function squares(frame: HTMLElement): SVGPathElement[] {
  return (
    [
      ...frame.querySelectorAll<SVGPathElement>(
        '[data-slot="chart-plot"] svg path',
      ),
    ]
      .filter(path => {
        const fill = path.getAttribute('fill') ?? '';
        return fill !== '' && fill !== 'none' && !fill.startsWith('url(');
      })
      // A square of the map is drawn taller than wide — the library squeezes
      // longitude by its aspect scale (0.75) — and small: not the tooltip's
      // frame or the scale's labels.
      .filter(path => {
        const box = path.getBoundingClientRect();
        const tall = box.height / box.width;
        return box.width > 8 && box.width < 80 && tall > 1.2 && tall < 1.5;
      })
      .sort(
        (a, b) =>
          a.getBoundingClientRect().left - b.getBoundingClientRect().left,
      )
  );
}

/**
 * The six squares of the drawn map. `data-drawn` is set by the first drawing
 * and there is no second one: the page's preset is on `<html>` before the
 * story renders (`preview.tsx`), so the chart reads its theme once.
 */
const drawnSquares = (frame: HTMLElement): Promise<SVGPathElement[]> =>
  waitFor(() => {
    const found = squares(frame);
    expect(found).toHaveLength(FIXTURE_PROVINCES.length);
    return found;
  });

const rgbOf = (text: string | null) =>
  text && parse(text) ? formatRgb(parse(text)!) : '';

/** 追问菜单：它弹在文档上，不在画布里；等它淡进来。 */
const drillMenu = () =>
  waitFor(() => {
    const found = document.body.querySelector<HTMLElement>(
      '[data-slot="drill-menu"]',
    );
    expect(found).not.toBeNull();
    expect(found).toBeVisible();
    return found!;
  });

/** 按下的那一省，用菜单标题与「正在显示」那条共用的词说出来。 */
const GUANGDONG = `省份 ${zhCN['label.relation.is']} 广东省`;

const SPLIT_TITLE = formatMessage(zhCN, 'label.drill.titled', {
  subject: '各省 GMV（近 12 个月）',
  group: GUANGDONG,
});

const RECORDS_TITLE = formatMessage(zhCN, 'label.drill.titled', {
  subject: '交易订单',
  group: GUANGDONG,
});

const appliedBar = (canvasElement: HTMLElement) =>
  within(canvasElement).getByRole('region', {
    name: zhCN['label.applied.title'],
  });

/** 读屏表的第一列：一组一行。 */
const readingNames = (canvasElement: HTMLElement) =>
  readingOf(canvasElement).map(row => row[0]);

/**
 * 按 GMV 上色：五个省在地图上，另外 26 个省不在这张合成的地图上、图上方说出
 * 个数；GMV 最高的广东最深，越往后越浅，台湾省没有数、是地的底色。色标在下，
 * 两端写着最低与最高。
 */
export const ChinaProvincesShaded: Story = {
  name: '中国省级（合成地图）：按 GMV 上色',
  args: { dataset: 'provinces' },
  beforeEach: registerFixtureChina,
  play: async ({ canvasElement }) => {
    const frame = await mapReady(canvasElement);
    await expect(frame).toHaveAttribute('data-marks', '5');
    await expect(
      frame.querySelector('[data-slot="map-notes"]'),
    ).toHaveTextContent(
      zhCN['label.chart.map.unplaced'].replace('{count}', '26'),
    );
    const rows = readingOf(canvasElement);
    await expect(rows).toHaveLength(31);
    await expect(rows.slice(0, 5).map(row => row[0])).toEqual(
      FIXTURE_PROVINCES.slice(0, 5),
    );
    await expect(rows.map(row => row[0])).not.toContain('台湾省');

    const drawn = await drawnSquares(frame);
    const fills = drawn.map(path => rgbOf(path.getAttribute('fill')));
    const first = rgbOf(getComputedStyle(frame).getPropertyValue('--chart-1'));
    await expect(fills[0]).toBe(first);
    // Further from the full slot the lower the GMV; the area with no number
    // further than any measured one.
    const distance = differenceEuclidean('oklab');
    const away = fills.map(fill => distance(fill, first));
    const values = rows.slice(0, 5).map(row => amountOf(row[1] ?? ''));
    for (let at = 1; at < 5; at++) {
      await expect(values[at]!).toBeLessThan(values[at - 1]!);
      await expect(away[at]!).toBeGreaterThan(away[at - 1]!);
    }
    await expect(away[5]!).toBeGreaterThan(away[4]!);

    // The scale under the map: a bar shaded from palest to the full slot,
    // with the lowest and the highest written at its ends.
    const plot = frame.querySelector('[data-slot="chart-plot"] svg')!;
    await expect(
      [...plot.querySelectorAll('path')].some(path =>
        (path.getAttribute('fill') ?? '').startsWith('url('),
      ),
    ).toBe(true);
    const ends = [...plot.querySelectorAll('text')]
      .map(text => text.textContent ?? '')
      .filter(text => text !== '');
    await expect(ends).toHaveLength(2);
    for (const end of ends) await expect(end).toMatch(/^¥/);
  },
};

/** 按下广东：追问菜单三项；「查看这些记录」开出广东的订单。 */
export const ChinaProvinceRecords: Story = {
  name: '中国省级（合成地图）：查看这些记录',
  args: { dataset: 'provinces' },
  beforeEach: registerFixtureChina,
  play: async ({ canvasElement }) => {
    const frame = await mapReady(canvasElement);
    const [guangdong] = await drawnSquares(frame);
    pressMark(guangdong!);
    const menu = await drillMenu();
    await expect(
      menu.querySelector('[data-slot="drill-group"]'),
    ).toHaveTextContent(GUANGDONG);
    await expect(
      within(menu)
        .getAllByRole('menuitem')
        .map(item => item.textContent),
    ).toEqual([
      zhCN['label.drill.records'],
      zhCN['label.drill.split'],
      zhCN['label.drill.focus'],
    ]);
    await userEvent.click(
      within(menu).getByRole('menuitem', {
        name: zhCN['label.drill.records'],
      }),
    );
    await findDataTable(canvasElement);
    await waitFor(() =>
      expect(
        within(canvasElement).getByRole('heading', {
          level: 2,
          name: RECORDS_TITLE,
        }),
      ).toBeVisible(),
    );
    await waitFor(() =>
      expect(
        within(appliedBar(canvasElement)).getByText(GUANGDONG),
      ).toBeVisible(),
    );
  },
};

/**
 * 按下广东，「按其他维度细分…」→「渠道」：同一个问题只问广东、按渠道分。
 * 渠道不是地图上的地区，所以细分画成按渠道的柱（这个形状读得最好的图），
 * 而不是一张什么都没画上的地图。
 */
export const ChinaProvinceSplit: Story = {
  name: '中国省级（合成地图）：按其他维度细分',
  args: { dataset: 'provinces' },
  beforeEach: registerFixtureChina,
  play: async ({ canvasElement }) => {
    const frame = await mapReady(canvasElement);
    const [guangdong] = await drawnSquares(frame);
    pressMark(guangdong!);
    const menu = await drillMenu();
    await userEvent.hover(
      within(menu).getByRole('menuitem', { name: zhCN['label.drill.split'] }),
    );
    const split = await waitFor(() => {
      const found = document.body.querySelector<HTMLElement>(
        '[data-slot="dropdown-menu-sub-content"]',
      );
      expect(found).not.toBeNull();
      return found!;
    });
    const options = within(split)
      .getAllByRole('menuitem')
      .map(item => item.textContent);
    await expect(options).toContain('渠道');
    // Already split by the province: not offered again.
    await expect(options).not.toContain('省份');
    await userEvent.click(
      within(split).getByRole('menuitem', { name: '渠道' }),
    );

    await waitFor(() =>
      expect(
        within(canvasElement).getByRole('heading', {
          level: 2,
          name: SPLIT_TITLE,
        }),
      ).toBeVisible(),
    );
    await waitFor(() =>
      expect(
        within(appliedBar(canvasElement)).getByText(GUANGDONG),
      ).toBeVisible(),
    );
    await waitFor(() =>
      expect([...readingNames(canvasElement)].sort()).toEqual(
        ['PC 商城', '分销', '微信小程序', '直播间', '自有 App'].sort(),
      ),
    );
    await waitFor(() => {
      expect(mapFrame(canvasElement)).toBeNull();
      expect(
        canvasElement.querySelector('[data-slot="chart"]'),
      ).toHaveAttribute('data-chart', 'bar');
    });
  },
};

/** 中国省级分析，宿主没有注册地图：图说没有，地图的磁贴置灰。 */
export const ChinaNoMapSaid: Story = {
  ...NoMapSaid,
  name: '中国省级：没有注册地图',
  args: { dataset: 'provinces' },
};
