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
  DERIVED_KINDS,
  MAX_MOVING_WINDOW,
  REFERENCE_STATISTICS,
  type CartesianSpec,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';

/**
 * What a cartesian chart draws over its marks, checked for shape (D33
 * batch B): every reference line and band hangs on an axis that has a
 * series, a line stands at a number or at a statistic of a metric drawn on
 * its axis, a band runs from a number up to a larger one, and a derived
 * series is a known kind over a metric drawn, a moving average over a
 * window of two points or more.
 *
 * What depends on the rows — a running total over rows cut short, a
 * statistic over a split — is no finding here: the config is sound, the
 * result is not whole, and the kernel leaves the line out and says why
 * (`derivedGap`, `placeLines`).
 */
export function referenceIssues(spec: CartesianSpec, path: IssuePath): Issue[] {
  const issues: Issue[] = [];
  const axes = new Set(spec.series.map(series => series.axis ?? 'left'));
  const drawnOn = (metric: string | undefined, axis: 'left' | 'right') =>
    spec.series.some(
      series => series.metric === metric && (series.axis ?? 'left') === axis,
    );

  (spec.referenceLines ?? []).forEach((line, index) => {
    const at = [...path, 'referenceLines', index];
    if (!axes.has(line.axis))
      issues.push(issue('chart.referenceLine.empty-axis', [...at, 'axis']));
    if (line.statistic === undefined) {
      if (typeof line.value !== 'number' || !Number.isFinite(line.value))
        issues.push(
          issue('chart.referenceLine.value-missing', [...at, 'value']),
        );
      return;
    }
    if (!(REFERENCE_STATISTICS as readonly string[]).includes(line.statistic))
      issues.push(
        issue('chart.referenceLine.statistic-unknown', [...at, 'statistic']),
      );
    else if (!drawnOn(line.metric, line.axis))
      issues.push(
        issue('chart.referenceLine.metric-not-drawn', [...at, 'metric'], {
          metric: line.metric ?? '',
        }),
      );
  });

  (spec.referenceBands ?? []).forEach((band, index) => {
    const at = [...path, 'referenceBands', index];
    if (!axes.has(band.axis))
      issues.push(issue('chart.referenceLine.empty-axis', [...at, 'axis']));
    if (
      !Number.isFinite(band.from) ||
      !Number.isFinite(band.to) ||
      !(band.from < band.to)
    )
      issues.push(issue('chart.referenceBand.order', [...at, 'to']));
  });

  (spec.derived ?? []).forEach((derived, index) => {
    const at = [...path, 'derived', index];
    if (!(DERIVED_KINDS as readonly string[]).includes(derived.kind)) {
      issues.push(issue('chart.derived.kind-unknown', [...at, 'kind']));
      return;
    }
    if (!spec.series.some(series => series.metric === derived.metric))
      issues.push(
        issue('chart.derived.metric-not-drawn', [...at, 'metric'], {
          metric: derived.metric,
        }),
      );
    if (
      derived.window !== undefined &&
      (derived.kind !== 'moving-average' ||
        !Number.isInteger(derived.window) ||
        derived.window < 2 ||
        derived.window > MAX_MOVING_WINDOW)
    )
      issues.push(
        issue('chart.derived.window', [...at, 'window'], {
          max: MAX_MOVING_WINDOW,
        }),
      );
  });
  return issues;
}
