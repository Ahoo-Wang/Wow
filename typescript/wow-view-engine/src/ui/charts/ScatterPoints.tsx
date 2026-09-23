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
  CartesianGrid,
  LabelList,
  Scatter,
  ScatterChart,
  XAxis,
  YAxis,
  ZAxis,
} from 'recharts';
import { useChartMotion } from './motion.js';
import type { ScatterData } from '../../analysis/index.js';
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from '../components/chart.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { asImage } from './asImage.js';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { pointAnchor } from '../analysis/DrillMenu.js';
import type { FamilyProps } from './family.js';
import { color } from './palette.js';
import { TooltipValue } from './TooltipValue.js';
import { allWhole, CHART_MARGIN, categoryTick } from './axis.js';

/**
 * The area of a point sized by a third metric, in square pixels, from the
 * smallest to the largest (recharts' `ZAxis` range): radii of about 3.6 and
 * 9px. Without one every point is recharts' own 64, a 9px marker.
 */
const POINT_AREA: [number, number] = [40, 260];

/**
 * Room between the plot's edge and the extremes of the data, in pixels.
 *
 * A point is a circle centred on its value, so the one at the largest value
 * hung half out of the plot and the chart's own box cut it (the 2026-09-23
 * audit). Each end of both scales is held in by the largest radius and a
 * little air — the axis `padding` recharts offers for exactly this, rather
 * than a domain stretched by a guessed fraction, which would also have put
 * the ticks on odd numbers.
 */
const POINT_ROOM = 12;

/**
 * Up to this many points, each is named where it is drawn; past it, names
 * would run into one another, and the tooltip names the one pointed at.
 * A few points are a few named things — 「华东」 and 「华南」 — and a
 * scatter that only says "a dot at (3, ¥1,200)" makes the reader hover each.
 */
const NAMED_POINTS = 8;

/** The height a name above a point takes, kept clear at the plot's top. */
const NAME_ROOM = 14;

export function ScatterPoints({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
}: FamilyProps<ScatterData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const category = spec?.scatter?.category;
  const rows = data.points.map(point => ({
    name: label(category, point.category),
    x: point.x,
    y: point.y,
    ...(point.size === undefined ? {} : { size: point.size }),
  }));
  // Both axes carry a metric each, so their ticks and the tooltip read as
  // those columns read rather than as bare numbers.
  const measured: Record<string, string | undefined> = {
    x: spec?.scatter?.x,
    y: spec?.scatter?.y,
    size: spec?.scatter?.size,
  };
  // Each axis is titled as the table's header names its column — the one
  // thing that says which metric runs which way; recharts also names a
  // point's tooltip rows by the axis they measure along.
  const xTitle = column(measured.x);
  const yTitle = column(measured.y);
  const named = rows.length <= NAMED_POINTS;
  // A third metric is drawn as the points' size, and only then: without one
  // there is no size axis, so no row of 「1」 under every tooltip.
  const sized = measured.size !== undefined;

  return (
    <ChartContainer
      config={{
        points: {
          label: messages.label('label.chart.points'),
          color: color(0),
        },
      }}
      className={cn('min-h-52 w-full', className)}
    >
      <ScatterChart margin={CHART_MARGIN} {...asImage(name)}>
        <CartesianGrid />
        <XAxis
          type="number"
          dataKey="x"
          name={xTitle ?? messages.label('label.chart.column.x')}
          // A count between 0 and 2 otherwise ticks at 0.5 and 1.5, which the
          // count's own format writes 「1」 and 「2」: 0 1 1 2 2.
          allowDecimals={!allWhole(data.points.map(point => point.x))}
          padding={{ left: POINT_ROOM, right: POINT_ROOM }}
          tickFormatter={(value: number) => label(measured.x, value)}
          // The title sits under the ticks, inside an axis made tall enough
          // for both.
          {...(xTitle === undefined
            ? {}
            : {
                height: 44,
                label: { value: xTitle, position: 'insideBottom' },
              })}
        />
        <YAxis
          type="number"
          dataKey="y"
          name={yTitle ?? messages.label('label.chart.column.y')}
          width="auto"
          allowDecimals={!allWhole(data.points.map(point => point.y))}
          padding={{
            top: POINT_ROOM + (named ? NAME_ROOM : 0),
            bottom: POINT_ROOM,
          }}
          tickFormatter={(value: number) => label(measured.y, value)}
          // Turned along the axis, the title is centred `offset` in from the
          // drawing's left edge, so it needs half a line of room there: at
          // recharts' own 5 its first pixels fell outside the `svg`.
          {...(yTitle === undefined
            ? {}
            : {
                label: {
                  value: yTitle,
                  angle: -90,
                  position: 'insideLeft',
                  offset: 10,
                },
              })}
        />
        {sized && (
          <ZAxis
            type="number"
            dataKey="size"
            name={column(measured.size)}
            range={POINT_AREA}
          />
        )}
        <ChartTooltip
          content={
            <ChartTooltipContent
              // A point is one group: the tooltip's heading is which one.
              labelFormatter={(_, payload) =>
                String(payload?.[0]?.payload?.name ?? '')
              }
              formatter={(value, name, item) => (
                <TooltipValue
                  color={item.color}
                  name={name}
                  value={label(measured[String(item.dataKey)], value)}
                />
              )}
            />
          }
        />
        <Scatter
          data={rows}
          fill="var(--color-points)"
          isAnimationActive={animate}
          className={onPick ? 'cursor-pointer' : undefined}
          onClick={
            onPick &&
            ((_: unknown, index: number, event: ReactMouseEvent) => {
              const point = data.points[index];
              if (!point || category === undefined) return;
              onPick(
                { [category]: point.category },
                pointAnchor(event.nativeEvent),
              );
            })
          }
        >
          {named && (
            <LabelList
              dataKey="name"
              position="top"
              className="fill-foreground text-xs"
              stroke="none"
              // The name is already text, as the category's column reads it.
              formatter={(value: unknown) =>
                typeof value === 'string' ? categoryTick(value) : ''
              }
            />
          )}
        </Scatter>
      </ScatterChart>
    </ChartContainer>
  );
}
