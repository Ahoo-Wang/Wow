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

import type { ChartSpec } from '../../model/index.js';

/**
 * Where the legend goes, as the chart library takes it, or nothing when
 * there is to be none. `auto` — and no setting — leaves it to the family:
 * a pie always has one, a cartesian chart only once there is more than one
 * series to tell apart. A legend on the right stands as a column.
 */
export function legendPlacement(
  legend: ChartSpec['legend'],
  auto: boolean,
):
  | {
      props: {
        verticalAlign: 'top' | 'bottom' | 'middle';
        align?: 'right';
        layout?: 'vertical';
      };
      className?: string;
    }
  | undefined {
  const where = legend ?? 'auto';
  if (where === 'none') return undefined;
  if (where === 'auto')
    return auto ? { props: { verticalAlign: 'bottom' } } : undefined;
  if (where === 'right')
    return {
      props: { verticalAlign: 'middle', align: 'right', layout: 'vertical' },
      className: 'flex-col items-start pt-0 pl-3',
    };
  return { props: { verticalAlign: where } };
}

/**
 * Where a legend drawn beside the chart goes (`ChartLegend`), or nowhere.
 * `auto` puts it on top, before the marks, once there is more than one
 * series to tell apart — where Metabase puts it, and where a reader looks
 * first; a single series is named by the axis title and the chart's name.
 */
export function legendAt(
  legend: ChartSpec['legend'],
  auto: boolean,
): 'top' | 'bottom' | 'right' | undefined {
  const where = legend ?? 'auto';
  if (where === 'none') return undefined;
  if (where === 'auto') return auto ? 'top' : undefined;
  return where;
}
