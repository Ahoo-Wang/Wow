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

import type { DataViewDefinition, FilterTree } from '../../model/index.js';
import type { ValueCandidates } from '../../analysis/index.js';
import type {
  ValueCandidateSource,
  ValueCandidateSources,
} from '../valueCandidates.js';

/**
 * One field a filter is wired to, as a candidate source is asked about it:
 * the panel it is on, the definition it belongs to, and the condition its
 * values are counted under — the host's scope, in that panel's names.
 */
export interface CandidateTarget {
  panelId: string;
  definition: DataViewDefinition;
  field: string;
  scope: () => FilterTree | null;
}

/** Builds the candidate sources of one definition under one scope. */
export type CandidateSourceFactory = (
  definition: DataViewDefinition,
  scope: () => FilterTree | null,
) => ValueCandidateSources;

/**
 * What a text filter offers to pick from (D22 G, 「值从哪来：接上的字段」):
 * the values of every field it is wired to, counted from the data the way a
 * text condition's are (`ValueCandidateSources`, #1768), one list across the
 * board. A field wired on two panels of the same data is asked once; the same
 * value in two datasets is one entry, its counts added — the records the
 * filter would find across the board.
 *
 * A source is handed out once per filter for as long as it asks the same
 * fields, so an editor may key an effect on it; the answers belong to the
 * scope they were asked under and are forgotten with it (`reset`).
 */
export class FilterCandidates {
  private readonly panels = new Map<string, ValueCandidateSources>();
  private readonly merged = new Map<
    string,
    { key: string; source: ValueCandidateSource | null }
  >();

  constructor(private readonly make: CandidateSourceFactory | undefined) {}

  /** The source of one filter over its targets; `null` when none offers any. */
  of(
    name: string,
    targets: readonly CandidateTarget[],
  ): ValueCandidateSource | null {
    const make = this.make;
    if (!make) return null;
    const unique = new Map<string, CandidateTarget>();
    for (const target of targets)
      unique.set(`${target.definition.id}\u0000${target.field}`, target);
    const key = [...unique.entries()]
      .map(([at, target]) => `${at}\u0000${target.panelId}`)
      .join('\u0001');
    const held = this.merged.get(name);
    if (held?.key === key) return held.source;

    const offered = [...unique.values()].flatMap(target => {
      const at = `${target.panelId}\u0000${target.definition.id}`;
      let sources = this.panels.get(at);
      if (!sources) {
        sources = make(target.definition, target.scope);
        this.panels.set(at, sources);
      }
      const source = sources.of(target.field);
      return source ? [source] : [];
    });
    const source: ValueCandidateSource | null =
      offered.length === 0
        ? null
        : {
            field: name,
            search: async (query, signal) =>
              mergeAnswers(
                await Promise.all(
                  offered.map(each => each.search(query, signal)),
                ),
              ),
          };
    this.merged.set(name, { key, source });
    return source;
  }

  /** Forgets every answer: the scope they were asked under changed. */
  reset(): void {
    for (const sources of this.panels.values()) sources.reset();
  }
}

/** Several answers as one: the same value once, its counts added. */
function mergeAnswers(answers: readonly ValueCandidates[]): ValueCandidates {
  const counts = new Map<string, number>();
  for (const answer of answers)
    for (const { value, count } of answer.values)
      counts.set(value, (counts.get(value) ?? 0) + count);
  const values = [...counts.entries()]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) =>
      a.count !== b.count ? b.count - a.count : a.value < b.value ? -1 : 1,
    );
  return { values, complete: answers.every(answer => answer.complete) };
}
