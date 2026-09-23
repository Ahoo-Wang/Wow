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

import { Cell, LabelList, Pie, PieChart } from 'recharts';
import { useChartMotion } from './motion.js';
import { groupKeyText, type PieData } from '../../analysis/index.js';
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
import { useSurfaceDisplay } from '../ViewSurface.js';
import { formatValue } from './axis.js';
import { asImage } from './asImage.js';
import { legendPlacement } from './legend.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import type { FamilyProps } from './family.js';
import { OTHER_COLOR, colorOf } from './palette.js';
import { TooltipValue } from './TooltipValue.js';

export function PieSlices({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  cutShort,
}: FamilyProps<PieData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  /**
   * A pie is shares of a whole, so each slice says its share (audit P0-10):
   * a wedge's angle is the one reading a pie asks for, and the eye is bad at
   * it. The whole is the slices drawn — a metric that went negative has no
   * share to give, so it has none. A slice under 3% is a sliver its label
   * would crowd; its share is in the tooltip and the reading table.
   */
  const whole = data.slices.reduce(
    (sum, slice) => (slice.value > 0 ? sum + slice.value : sum),
    0,
  );
  const shareOf = (value: number) =>
    whole > 0 && value >= 0 ? value / whole : undefined;
  const percent = (share: number) => formatValue(share, 'percent', locale);
  // Same rule as the cartesian series: a category value becomes an identifier
  // before it can reach the style element, and stays a label.
  const rows = data.slices.map((slice, index) => ({
    key: `p${index}`,
    name:
      slice.other === true
        ? messages.label('label.chart.other')
        : label(spec?.pie?.category, slice.category),
    value: slice.value,
    // What the slice's label says: its share, and its value too when the
    // spec asks for labels.
    caption: (() => {
      const share = shareOf(slice.value);
      if (share === undefined || share < 0.03) return '';
      return spec?.labels === true
        ? `${label(spec?.pie?.value, slice.value)} · ${percent(share)}`
        : percent(share);
    })(),
    // A slice is named by its category through `groupKeyText`, the spelling
    // the kernel labels a split series with, so one key colours a category
    // in either chart — `null` is the empty string there, where `String(...)`
    // would have looked it up under `null` — rather than as the legend shows
    // it. The merged remainder is no category anyone could have coloured, so
    // it is the neutral whatever the spec says, and takes no slot.
    color:
      slice.other === true
        ? OTHER_COLOR
        : colorOf(spec, index, groupKeyText(slice.category)),
  }));
  const config = Object.fromEntries(
    rows.map(row => [row.key, { label: row.name, color: row.color }]),
  ) satisfies ChartConfig;
  // A pie without its legend is unreadable, so "auto" is a legend.
  const legend = legendPlacement(spec?.legend, true);
  const measure = column(spec?.pie?.value);

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
                  // A slice measures one metric, so it reads as that column,
                  // and says its share beside it.
                  value={(() => {
                    const share =
                      typeof value === 'number' ? shareOf(value) : undefined;
                    const text = label(spec?.pie?.value, value);
                    return share === undefined
                      ? text
                      : `${text} · ${percent(share)}`;
                  })()}
                />
              )}
            />
          }
        />
        <Pie
          data={rows}
          dataKey="value"
          nameKey="key"
          isAnimationActive={animate}
          innerRadius={spec?.pie?.donut === true ? '55%' : 0}
          className={onPick ? 'cursor-pointer' : undefined}
          // The merged remainder is not a group of the result: it stands
          // for several, and no one condition selects them.
          onClick={
            onPick &&
            ((_, index, event) => {
              const slice = data.slices[index];
              const category = spec?.pie?.category;
              if (!slice || slice.other === true || category === undefined)
                return;
              onPick({ [category]: slice.category }, pointAnchor(event));
            })
          }
        >
          {rows.map(row => (
            <Cell key={row.key} fill={row.color} />
          ))}
          <LabelList
            dataKey="caption"
            position="outside"
            className="fill-foreground text-xs"
            stroke="none"
          />
        </Pie>
        {legend && (
          <ChartLegend
            {...legend.props}
            // The legend is the key to the picture, so it leads with what
            // the slices measure — the column's own title — and, when the
            // rows are the first groups of more, that the shares are of
            // those groups: the remainder is not on the pie, and a share
            // that read as of the whole would be wrong by exactly it.
            content={props => (
              <div
                data-slot="pie-legend"
                className={cn(
                  'flex flex-wrap items-center justify-center gap-x-4 gap-y-1',
                  legend.props.layout === 'vertical'
                    ? 'flex-col items-start pl-3'
                    : legend.props.verticalAlign === 'top'
                      ? 'pb-3'
                      : 'pt-3',
                )}
              >
                {measure !== undefined && (
                  <span
                    data-slot="pie-measure"
                    className="text-muted-foreground"
                  >
                    {cutShort
                      ? `${measure} · ${messages.label('label.chart.share-basis')}`
                      : measure}
                  </span>
                )}
                <ChartLegendContent
                  payload={props.payload}
                  verticalAlign={props.verticalAlign}
                  nameKey="key"
                  className={cn(legend.className, 'p-0')}
                />
              </div>
            )}
          />
        )}
      </PieChart>
    </ChartContainer>
  );
}
