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

import type { AnalysisViewConfig, RecordData } from '../model/index.js';
import { num, seriesKey } from './chartRows.js';

export interface FunnelStage {
  label: string;
  value: number;
  /**
   * For a stage taken from a dimension, that dimension's value as the rows
   * hold it — what a press on the stage names the group by (D33 batch C),
   * the stage's key where no row has it. Absent for a metric's stage, which
   * is no group.
   */
  group?: unknown;
  /**
   * This stage against the one before it — 1 for the first stage, nothing
   * when the one before is 0, where there is nothing to convert from.
   */
  conversion?: number;
  /**
   * This stage against the first — nothing when the first is 0. The last
   * stage's is the funnel's overall conversion.
   */
  share?: number;
  /**
   * What the one before it had and this stage has not: the one before's
   * value minus this one's — negative where this stage is bigger. Absent on
   * the first stage, which has nothing before it.
   */
  drop?: number;
}

export interface FunnelData {
  type: 'funnel';
  stages: FunnelStage[];
  /**
   * Each stage is "reached at least this stage" — itself and every later
   * one added up — rather than the stage's own rows, because the spec asked
   * for it (`FunnelStages.cumulative`). Its values then differ from the
   * table's, and a drawing that does not say so reads as a wrong number.
   */
  cumulative?: true;
  /**
   * The stage whose drop from the one before is the largest share of the
   * one before — the step where the funnel loses the most, the first of
   * equals. Absent when no stage lost anything, or none can say how much of
   * what it had (the one before was 0).
   */
  largestDrop?: number;
}

export function shapeFunnel(
  spec: NonNullable<AnalysisViewConfig['chart']['funnel']>,
  rows: readonly RecordData[],
): FunnelData {
  const raw: FunnelStage[] =
    spec.stages.from === 'metrics'
      ? spec.stages.items.map(item => ({
          label: item.label ?? item.metric,
          value: num(rows[0] ?? {}, item.metric) ?? 0,
        }))
      : stagesFromGroup(spec.stages, rows);

  const stages = withConversion(raw);
  const largestDrop = largestDropOf(stages);
  return {
    type: 'funnel',
    stages,
    ...(largestDrop === undefined ? {} : { largestDrop }),
    ...(spec.stages.from === 'group' && spec.stages.cumulative === true
      ? { cumulative: true as const }
      : {}),
  };
}

/**
 * The stages a group's values make, in the business order, each its own
 * rows' number — the same number the table and the bar chart show beside
 * that value, which is what Metabase draws too.
 *
 * Accumulating was the default once, on the reading that each object sits
 * in exactly one stage and "reached at least here" is this stage and every
 * later one. That holds for a status an order moves through and for nothing
 * else a category can be: an event's type, a warehouse. The compensation
 * service's 「事件类型分布」 drew 「首次失败 1,831,229」 — the seven types
 * added up — beside a table that said 65.9万. So a funnel accumulates only
 * when asked, and then says so (`FunnelData.cumulative`).
 */
function stagesFromGroup(
  stages: Extract<
    NonNullable<AnalysisViewConfig['chart']['funnel']>['stages'],
    { from: 'group' }
  >,
  rows: readonly RecordData[],
): FunnelStage[] {
  const byCategory = new Map<string, { value: number; group: unknown }>();
  for (const row of rows)
    byCategory.set(seriesKey(row[stages.category]), {
      value: num(row, stages.value) ?? 0,
      group: row[stages.category],
    });

  // The configured order names group values, so it is read through the same
  // key: a stage written as `1` is the string `1`, never the number.
  const ordered = stages.order.map(key => {
    const found = byCategory.get(seriesKey(key));
    return { label: key, value: found?.value ?? 0, group: found?.group ?? key };
  });
  if (stages.cumulative !== true) return ordered;

  // Asked to, each object is read as sitting in exactly one stage, so
  // "reached at least here" is the sum of this stage and every later one.
  let running = 0;
  return [...ordered]
    .reverse()
    .map(stage => {
      running += stage.value;
      return { ...stage, value: running };
    })
    .reverse();
}

/**
 * Each stage against the one before it and against the first, and what it
 * lost on the way from the one before. Both conversions, always: the one
 * before says where the funnel leaks, the first says how much of what came
 * in is still here, and a reader asks both of one drawing (2026-09-25).
 * They are read off the numbers drawn, so a funnel that accumulates
 * (`cumulative`) converts its "reached at least" numbers, and its drop
 * between two stages is exactly the earlier stage's own rows — the ones
 * that stopped there.
 */
function withConversion(stages: FunnelStage[]): FunnelStage[] {
  if (stages.length === 0) return stages;
  const first = stages[0].value;
  return stages.map((stage, index) => {
    const share = first === 0 ? undefined : stage.value / first;
    if (index === 0)
      return {
        ...stage,
        conversion: 1,
        ...(share === undefined ? {} : { share }),
      };
    const before = stages[index - 1].value;
    return {
      ...stage,
      ...(before === 0 ? {} : { conversion: stage.value / before }),
      ...(share === undefined ? {} : { share }),
      drop: before - stage.value,
    };
  });
}

/** The stage that lost the largest share of the one before it, if any lost. */
function largestDropOf(stages: readonly FunnelStage[]): number | undefined {
  let found: number | undefined;
  let deepest = 0;
  stages.forEach((stage, index) => {
    if (index === 0 || stage.conversion === undefined) return;
    const lost = 1 - stage.conversion;
    if (lost > deepest) {
      deepest = lost;
      found = index;
    }
  });
  return found;
}
