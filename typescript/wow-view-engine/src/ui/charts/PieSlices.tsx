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

import { Cell, Pie, PieChart } from 'recharts';
import type { PieData } from '../../analysis/index.js';
import {
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from '../components/chart.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { asImage } from './asImage.js';
import { labelOf, type FamilyProps } from './family.js';
import { color, colorOf } from './palette.js';
import { TooltipValue } from './TooltipValue.js';

export function PieSlices({
  data,
  spec,
  className,
  label,
  name,
}: FamilyProps<PieData>) {
  const messages = useViewMessages();
  // Same rule as the cartesian series: a category value becomes an identifier
  // before it can reach the style element, and stays a label.
  const rows = data.slices.map((slice, index) => ({
    key: `p${index}`,
    name:
      slice.other === true
        ? messages.label('label.chart.other')
        : label(spec?.pie?.category, slice.category),
    value: slice.value,
    // A slice is named by its category as the kernel labels it — `null` is
    // the empty string there, where `String(...)` would have looked it up
    // under `null` — rather than as the legend shows it. The merged
    // remainder is no category anyone could have coloured, so it keeps its
    // slot whatever the spec says.
    color:
      slice.other === true
        ? color(index)
        : colorOf(spec, index, labelOf(slice.category)),
  }));
  const config = Object.fromEntries(
    rows.map(row => [row.key, { label: row.name, color: row.color }]),
  ) satisfies ChartConfig;

  return (
    <ChartContainer
      config={config}
      className={cn('min-h-52 w-full', className)}
    >
      <PieChart {...asImage(name)}>
        <ChartTooltip
          content={
            <ChartTooltipContent
              nameKey="key"
              formatter={(value, key, item) => (
                <TooltipValue
                  color={item.payload?.color}
                  name={config[String(key)]?.label ?? key}
                  // A slice measures one metric, so it reads as that column.
                  value={label(spec?.pie?.value, value)}
                />
              )}
            />
          }
        />
        <Pie
          data={rows}
          dataKey="value"
          nameKey="key"
          innerRadius={spec?.pie?.donut === true ? '55%' : 0}
        >
          {rows.map(row => (
            <Cell key={row.key} fill={row.color} />
          ))}
        </Pie>
        <ChartLegend content={<ChartLegendContent nameKey="key" />} />
      </PieChart>
    </ChartContainer>
  );
}
