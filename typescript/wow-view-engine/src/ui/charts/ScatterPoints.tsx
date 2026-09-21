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
  Scatter,
  ScatterChart,
  XAxis,
  YAxis,
  ZAxis,
} from 'recharts';
import type { ScatterData } from '../../analysis/index.js';
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from '../components/chart.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { asImage } from './asImage.js';
import type { FamilyProps } from './family.js';
import { color } from './palette.js';

export function ScatterPoints({
  data,
  spec,
  className,
  label,
  name,
}: FamilyProps<ScatterData>) {
  const messages = useViewMessages();
  const rows = data.points.map(point => ({
    name: label(spec?.scatter?.category, point.category),
    x: point.x,
    y: point.y,
    size: point.size ?? 1,
  }));

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
      <ScatterChart {...asImage(name)}>
        <CartesianGrid />
        <XAxis type="number" dataKey="x" />
        <YAxis type="number" dataKey="y" />
        <ZAxis type="number" dataKey="size" range={[40, 260]} />
        <ChartTooltip content={<ChartTooltipContent nameKey="name" />} />
        <Scatter data={rows} fill="var(--color-points)" />
      </ScatterChart>
    </ChartContainer>
  );
}
