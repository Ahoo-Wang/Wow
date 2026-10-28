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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fitCharts } from '../src/analysis/index.js';
import type { AnalysisGroup, AnalysisMetric } from '../src/index.js';
import { ChartPicker } from '../src/ui/analysis/ChartPicker.js';
import { ViewSurface } from '../src/ui/index.js';
import {
  AggregationExpressionType,
  AggregationFunction,
  AggregationMetricType,
  AggregationGroupType,
} from '@ahoo-wang/wow-client/dsl';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const WAREHOUSE: AnalysisGroup = {
  type: AggregationGroupType.TERMS,
  field: 'warehouse',
  alias: 'warehouse',
};
const TOTAL: AnalysisMetric = {
  type: AggregationMetricType.NUMERIC,
  alias: 'total',
  function: AggregationFunction.SUM,
  expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
};
const fits = fitCharts({ groups: [WAREHOUSE], metrics: [TOTAL] });

function Picker() {
  const [picked, setPicked] = useState<'table' | keyof typeof fits>('bar');
  return (
    <ViewSurface>
      <ChartPicker
        fits={fits}
        picked={picked}
        onPick={setPicked}
        onOptions={() => {}}
      />
    </ViewSurface>
  );
}

const tiles = () => screen.getAllByRole('radio');
const typeOf = (tile: Element | null) => tile?.getAttribute('data-chart-type');
const available = (tile: Element) =>
  tile.getAttribute('aria-disabled') !== 'true';

/** Lays the tiles out `columns` to a row, 100 wide and 50 tall each. */
function laidOut(columns: number) {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
    function (this: HTMLElement) {
      const at = tiles().indexOf(this);
      const left = at < 0 ? 0 : (at % columns) * 100;
      const top = at < 0 ? 0 : Math.floor(at / columns) * 50;
      return DOMRect.fromRect({ x: left, y: top, width: 100, height: 50 });
    },
  );
}

function press(key: string) {
  fireEvent.keyDown(document.activeElement!, { key });
  return document.activeElement;
}

describe('the chart picker under the arrow keys (R2-99)', () => {
  it('goes a row down and up in the same column, as a grid does', () => {
    render(<Picker />);
    laidOut(2);
    const all = tiles();
    const first = all.findIndex(available);
    // The first tile that can draw with one below it that can too.
    const from = all.findIndex(
      (tile, at) => available(tile) && all[at + 2] && available(all[at + 2]!),
    );
    all[from]!.focus();

    const below = press('ArrowDown');
    expect(typeOf(below)).toBe(typeOf(all[from + 2]!));
    expect(below?.getAttribute('aria-checked')).toBe('true');
    expect(typeOf(press('ArrowUp'))).toBe(typeOf(all[from]!));

    // ← and → still step one tile, over the ones that cannot draw.
    all[first]!.focus();
    const next = all.findIndex((tile, at) => at > first && available(tile));
    expect(typeOf(press('ArrowRight'))).toBe(typeOf(all[next]!));
    expect(typeOf(press('ArrowLeft'))).toBe(typeOf(all[first]!));
  });

  it('stops at the first row rather than going round', () => {
    render(<Picker />);
    laidOut(2);
    const all = tiles();
    const first = all.findIndex(available);
    all[first]!.focus();
    expect(typeOf(press('ArrowUp'))).toBe(typeOf(all[first]!));
  });

  it('steps one tile where the tiles stand in no rows', () => {
    // jsdom lays nothing out: every tile at the origin.
    render(<Picker />);
    const all = tiles();
    const first = all.findIndex(available);
    const next = all.findIndex((tile, at) => at > first && available(tile));
    all[first]!.focus();
    expect(typeOf(press('ArrowDown'))).toBe(typeOf(all[next]!));
    expect(typeOf(press('ArrowUp'))).toBe(typeOf(all[first]!));
  });
});
