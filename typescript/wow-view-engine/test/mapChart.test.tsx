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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  fitChartSlots,
  fitCharts,
  leadMetric,
  shapeChart,
  switchChartType,
  validateChart,
} from '../src/analysis/index.js';
import type {
  AnalysisGroup,
  AnalysisMetric,
  ChartData,
  ChartSpec,
} from '../src/index.js';
import { ChartPicker } from '../src/ui/analysis/ChartPicker.js';
import { chartMaps, loadChartMap } from '../src/ui/charts/maps.js';
import { mapOption } from '../src/ui/charts/mapOption.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';
import {
  AnalysisChart,
  ViewSurface,
  registerChartMap,
  type ChartMapGeoJson,
} from '../src/ui/index.js';
import { analysisConfig } from './fixtures.js';

const unregister: (() => void)[] = [];
afterEach(() => {
  cleanup();
  for (const off of unregister.splice(0)) off();
});

const COUNTRY: AnalysisGroup = {
  type: 'TERMS',
  field: 'country',
  alias: 'country',
};
const MONTH: AnalysisGroup = {
  type: 'DATE_HISTOGRAM',
  field: 'at',
  alias: 'month',
  unit: 'MONTH',
};
const GMV: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'gmv',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};

/** Two squares side by side: a world of two countries. */
const SQUARES: ChartMapGeoJson = {
  type: 'FeatureCollection',
  features: [
    ['Norland', 0],
    ['Southia', 20],
  ].map(([name, x]) => ({
    type: 'Feature' as const,
    properties: { name },
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [x, 0],
          [Number(x) + 10, 0],
          [Number(x) + 10, 10],
          [x, 10],
          [x, 0],
        ],
      ],
    },
  })),
};

function offer(name = 'squares', load = () => Promise.resolve(SQUARES)) {
  const off = registerChartMap({ name, label: `The ${name}`, load });
  unregister.push(off);
  return off;
}

const spec: ChartSpec = {
  type: 'map',
  map: { region: 'country', value: 'gmv' },
};
const DATA: ChartData = {
  type: 'map',
  regions: [
    { group: 'Norland', value: 30 },
    { group: 'Atlantis', value: 20 },
    { group: 'Southia', value: 10 },
  ],
  low: 10,
  high: 30,
  omitted: 1,
};

describe('the map’s kernel (D41)', () => {
  it('fits one dimension of regions by value and a quantity', () => {
    expect(fitCharts({ groups: [COUNTRY], metrics: [GMV] }).map).toEqual({
      available: true,
    });
    expect(fitCharts({ groups: [MONTH], metrics: [GMV] }).map.reason).toBe(
      'chart.fit.needs-region',
    );
    expect(fitCharts({ groups: [], metrics: [GMV] }).map.reason).toBe(
      'chart.fit.needs-dimension',
    );
    expect(
      fitCharts({ groups: [COUNTRY, MONTH], metrics: [GMV] }).map.reason,
    ).toBe('chart.fit.needs-one-dimension');
  });

  it('keeps the map it names, and refuses a region that is no value', () => {
    const chart = fitChartSlots(
      { type: 'map', map: { region: 'gone', value: 'gone', map: 'world' } },
      [COUNTRY],
      [GMV],
    );
    expect(chart.map).toEqual({
      region: 'country',
      value: 'gmv',
      map: 'world',
    });
    expect(leadMetric(chart)).toBe('gmv');
    expect(
      switchChartType(
        { type: 'pie', pie: { category: 'country', value: 'gmv' } },
        'map',
      ).map,
    ).toEqual({ region: '', value: 'gmv' });
    const codes = (
      map: ChartSpec['map'],
      groups: AnalysisGroup[] = [COUNTRY],
    ) =>
      validateChart(
        analysisConfig({ groups, metrics: [GMV], chart: { type: 'map', map } }),
      ).map(issue => issue.code);
    expect(codes({ region: 'country', value: 'gmv' })).toEqual([]);
    expect(codes({ region: 'month', value: 'gmv' }, [MONTH])).toEqual([
      'chart.map.needs-region',
    ]);
    expect(codes({ region: 'country', value: 'gmv', map: ' ' })).toEqual([
      'chart.map.name-invalid',
    ]);
  });

  it('lists the regions largest first, and counts one with no number', () => {
    const data = shapeChart(
      analysisConfig({ groups: [COUNTRY], metrics: [GMV], chart: spec }),
      [
        { country: 'Southia', gmv: 10 },
        { country: 'Norland', gmv: 30 },
        { country: 'Nowhere', gmv: null },
      ],
    );
    expect(data).toEqual({
      type: 'map',
      regions: [
        { group: 'Norland', value: 30 },
        { group: 'Southia', value: 10 },
      ],
      low: 10,
      high: 30,
      omitted: 1,
    });
  });
});

describe('the host’s maps', () => {
  it('registers, replaces and takes a map back', async () => {
    const off = offer();
    expect(chartMaps().map(map => map.name)).toEqual(['squares']);
    const loaded = await loadChartMap('squares');
    expect([...loaded.regions]).toEqual(['Norland', 'Southia']);
    // Loaded once and kept.
    expect(await loadChartMap('squares')).toBe(loaded);
    // Another of the same name takes its place; the old one's take-back is
    // no longer its to make.
    offer('squares', () => Promise.resolve({ ...SQUARES, features: [] }));
    off();
    expect(chartMaps()).toHaveLength(1);
    await expect(loadChartMap('elsewhere')).rejects.toThrow(/elsewhere/);
  });

  it('refuses a geography with no named region as a failed load', async () => {
    offer('blank', () =>
      Promise.resolve({ type: 'FeatureCollection', features: [] }),
    );
    await expect(loadChartMap('blank')).rejects.toThrow(/no named region/);
  });

  it('asks a failed map again next time', async () => {
    const load = vi
      .fn<() => Promise<ChartMapGeoJson>>()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue(SQUARES);
    offer('flaky', load);
    await expect(loadChartMap('flaky')).rejects.toThrow('offline');
    await expect(loadChartMap('flaky')).resolves.toMatchObject({
      name: 'flaky',
    });
    expect(load).toHaveBeenCalledTimes(2);
  });
});

function draw(onPick = vi.fn()) {
  const view = render(
    <ViewSurface>
      <AnalysisChart data={DATA} spec={spec} onPick={onPick} />
    </ViewSurface>,
  );
  return { ...view, onPick };
}

const frame = (container: HTMLElement) =>
  container.querySelector<HTMLElement>(
    '[data-slot="chart"][data-chart="map"]',
  )!;

describe('a map drawn and read', () => {
  it('says there is no map when the host registered none', async () => {
    const { container } = draw();
    expect(frame(container).getAttribute('data-map')).toBe('missing');
    expect(
      container.querySelector('[data-slot="map-notes"]')?.textContent,
    ).toContain('No map is available to draw on');
    // The numbers are read all the same.
    expect(
      container.querySelectorAll('[data-slot="chart-reading"] tbody tr'),
    ).toHaveLength(3);
  });

  it('shades the regions it has, counts the ones it has not, and hands a press back', async () => {
    offer();
    const { container, onPick } = draw();
    await waitFor(() =>
      expect(frame(container).getAttribute('data-map')).toBe('ready'),
    );
    expect(frame(container).getAttribute('data-marks')).toBe('2');
    const notes = container.querySelector('[data-slot="map-notes"]')!;
    expect(notes.textContent).toContain('1 regions are not on this map');
    expect(notes.textContent).toContain('1 regions without a number');
    expect(container.textContent).toContain('highest Norland, 30');
    const area = await waitFor(() => {
      const found = [
        ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ].find(path =>
        /^rgba?\(38,\s*117,\s*211/.test(path.getAttribute('fill') ?? ''),
      );
      expect(found).toBeDefined();
      return found!;
    });
    const corners = [
      ...(area.getAttribute('d') ?? '').matchAll(
        /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
      ),
    ].map(([, x, y]) => [Number(x), Number(y)] as const);
    const at = {
      clientX:
        (Math.min(...corners.map(([x]) => x)) +
          Math.max(...corners.map(([x]) => x))) /
        2,
      clientY:
        (Math.min(...corners.map(([, y]) => y)) +
          Math.max(...corners.map(([, y]) => y))) /
        2,
    };
    const surface = container.querySelector(
      '[data-slot="chart-plot"] > div > div',
    )!;
    for (const type of ['mousemove', 'mousedown', 'mouseup', 'click']) {
      const event = new MouseEvent(type, { bubbles: true, ...at });
      Object.defineProperties(event, {
        offsetX: { value: at.clientX },
        offsetY: { value: at.clientY },
      });
      fireEvent(surface, event);
    }
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0]![0]).toEqual({ country: 'Norland' });
  });

  it('says so when the map could not be loaded', async () => {
    offer('squares', () => Promise.reject(new Error('offline')));
    const { container } = draw();
    await waitFor(() =>
      expect(
        container.querySelector('[data-slot="map-notes"]')?.textContent,
      ).toContain('The map could not be loaded'),
    );
  });

  it('greys the map tile until the host offers a map', () => {
    const fits = fitCharts({ groups: [COUNTRY], metrics: [GMV] });
    const picker = () =>
      render(
        <ViewSurface>
          <ChartPicker
            fits={fits}
            picked="bar"
            onPick={() => {}}
            onOptions={() => {}}
          />
        </ViewSurface>,
      );
    const tile = () =>
      screen
        .getAllByRole('radio')
        .find(found => found.getAttribute('data-chart-type') === 'map')!;
    picker();
    expect(tile().getAttribute('aria-disabled')).toBe('true');
    expect(within(tile()).getByText('No map is available here')).toBeDefined();
    cleanup();
    offer();
    picker();
    expect(tile().getAttribute('aria-disabled')).toBeNull();
  });
});

const theme: ChartTheme = {
  palette: ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'Geist',
  key: 'test',
  resolve: () => 'rgb(38, 117, 211)',
};

describe('the map’s option', () => {
  it('names a region in its tooltip, fades the rest under a highlight', () => {
    const option = mapOption(
      { ...DATA, low: 5, high: 5 } as Extract<ChartData, { type: 'map' }>,
      'squares',
      {
        spec,
        label: (_alias, value, compact) =>
          `${compact ? '~' : ''}${String(value)}`,
        column: alias => alias && `title:${alias}`,
        animate: false,
        pickable: false,
        highlight: row => row.country === 'Norland',
      },
      theme,
    );
    const formatter = (option.tooltip as { formatter: (p: unknown) => string })
      .formatter;
    const html = formatter({ name: 'Southia' });
    expect(html).toContain('title:gmv');
    expect(html).not.toContain('style=');
    expect(formatter({ name: 'Atlantis-free' })).toBe('');
    const data = (option.series as { data: object[] }[])[0]!.data;
    expect(data[0]).not.toHaveProperty('itemStyle');
    expect(data[1]).toHaveProperty('itemStyle');
    expect((option.visualMap as { max: number }).max).toBe(6);
  });
});
