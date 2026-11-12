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

/**
 * A bucket where no series has a number draws no tooltip. The axis tooltip
 * answers anywhere over the grid, and over an hour neither of two daily
 * lines has a value in it drew a box with the hour's name and no row: a
 * tooltip with nothing in it. A bucket where one series has a number still
 * reads that one, and skips the gap.
 */

import { describe, expect, it } from 'vitest';
import type { CartesianData, ChartSpec } from '../src/index.js';
import {
  cartesianOption,
  type CartesianContext,
} from '../src/ui/charts/cartesianOption.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';

const theme: ChartTheme = {
  ...CHART_FALLBACK,
  key: 'test',
  resolve: color => color,
};

const data: CartesianData = {
  type: 'cartesian',
  chart: 'line',
  points: [
    { x: '10', values: { today: 5, yesterday: 4 } },
    { x: '11', values: { today: null, yesterday: null } },
    { x: '12', values: { today: null, yesterday: 7 } },
  ],
  series: [
    { key: 'today', label: 'Today', metric: 'today' },
    { key: 'yesterday', label: 'Yesterday', metric: 'yesterday' },
  ],
};

const spec = {
  type: 'line',
  cartesian: {
    x: 'hour',
    series: [{ metric: 'today' }, { metric: 'yesterday' }],
  },
} as ChartSpec;

const context: CartesianContext = {
  spec,
  label: (alias, value) => `${alias}=${String(value)}`,
  column: () => undefined,
  locale: 'en',
  animate: false,
  pickable: false,
};

// The option is the library's loose shape.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const { tooltip } = cartesianOption(data, context, theme) as Record<
  string,
  any
>;

describe('the axis tooltip over a bucket', () => {
  it('draws nothing where no series has a number', () => {
    expect(tooltip.formatter([{ dataIndex: 1 }])).toBe('');
  });

  it('reads the one series that has, and both where both have', () => {
    const one: string = tooltip.formatter([{ dataIndex: 2 }]);
    expect(one).toContain('yesterday=7');
    expect(one).not.toContain('today=');
    const both: string = tooltip.formatter([{ dataIndex: 0 }]);
    expect(both).toContain('today=5');
    expect(both).toContain('yesterday=4');
  });
});
