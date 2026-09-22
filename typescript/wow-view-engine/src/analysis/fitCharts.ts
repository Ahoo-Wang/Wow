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
  CHART_TYPES,
  type AnalysisGroup,
  type AnalysisMetric,
  type ChartType,
} from '../model/index.js';
import { familyOf, type ChartUnfit, type ShapeFacts } from './chartFamilies.js';
import { isAdditiveMetric } from './validateChart.js';

export interface ChartFit {
  available: boolean;
  /** Why not, when not. */
  reason?: ChartUnfit;
  /** The one the shape reads best as; at most one type carries it. */
  recommended?: true;
}

/** The shape a chart is fitted to: what there is to draw. */
export interface ChartShape {
  groups: readonly AnalysisGroup[];
  metrics: readonly AnalysisMetric[];
}

/**
 * Which chart types can draw a result of this shape, and which one it reads
 * best as (K3). The capability decides which types exist at all (D4); this
 * decides which of them are greyed right now, and why — the answer to Q6.
 *
 * Each type answers with its family's fit (`CHART_FAMILIES` in
 * `chartFamilies.ts`), which is `validateChart`'s rules read forward; a
 * table draws anything and is not among them.
 */
export function fitCharts(shape: ChartShape): Record<ChartType, ChartFit> {
  const facts = shapeFacts(shape);
  const fits = Object.fromEntries(
    CHART_TYPES.map(type => {
      const reason = familyOf(type).unfit(facts);
      return [
        type,
        reason ? { available: false, reason } : { available: true },
      ];
    }),
  ) as Record<ChartType, ChartFit>;
  const best = recommend(facts);
  if (best) fits[best] = { ...fits[best], recommended: true };
  return fits;
}

function shapeFacts(shape: ChartShape): ShapeFacts {
  const groups = shape.groups.length;
  return {
    groups,
    metrics: shape.metrics.length,
    dated: groups === 1 && shape.groups[0]?.type === 'DATE_HISTOGRAM',
    additive: shape.metrics.some(isAdditiveMetric),
  };
}

/**
 * What the shape reads best as: a number is a card, a series over time is a
 * line, one dimension is bars, two are bars split by the second. Three or
 * more are a table's job, and nothing is recommended — every chart that
 * could take them is greyed, and a recommendation nobody can act on is
 * worse than none.
 *
 * The recommendation is never a greyed tile: the return type is narrowed to
 * the three whose fit the answer implies (a card with no dimension, the
 * cartesian family with one or two), and a test holds it to that over every
 * shape (test/fitCharts.test.ts「fitCharts」).
 */
function recommend({
  groups,
  dated,
}: ShapeFacts): Extract<ChartType, 'metric' | 'line' | 'bar'> | null {
  if (groups === 0) return 'metric';
  if (groups > 2) return null;
  if (dated) return 'line';
  return 'bar';
}

/** Every type, in the order the picker lays them out. */
export const CHART_PICKER_ORDER: readonly ChartType[] = CHART_TYPES;
