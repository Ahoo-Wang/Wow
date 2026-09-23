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

import type {
  AnalysisViewConfig,
  FieldDefinition,
  FilterTree,
} from '../model/index.js';
import {
  compileAnalysis,
  narrowValueCandidates,
  readValueCandidates,
  validateAnalysis,
  valueCandidateField,
  valueCandidateNarrowing,
  valueCandidatesConfig,
  type ValueCandidates,
} from '../analysis/index.js';
import type { KernelContext } from './execute.js';
import { abortWith } from './abort.js';
import { hasError } from './runtimeStore.js';
import { withScopeFilter } from './scope.js';

/**
 * The values a condition on one field may be picked from, counted from the
 * data (`valueCandidatesConfig`). Handed out by `ViewRuntime.valueCandidates`
 * once per field, so an editor may key an effect on it.
 */
export interface ValueCandidateSource {
  /** The field whose values these are, as the condition names it. */
  readonly field: string;
  /**
   * The most frequent values holding `query` (all of them while it is
   * blank). Rejects with what the source threw, and with the signal's reason
   * once it aborts.
   */
  search(query: string, signal?: AbortSignal): Promise<ValueCandidates>;
}

/**
 * Every candidate source of one data view, and what they have answered.
 *
 * An answer is kept for the life of the view, keyed by field and by what was
 * typed: a condition reopened, or a second condition on the same field, asks
 * nothing again. What narrows without asking is narrowed here — once the
 * unnarrowed list came back whole there is nothing more the source could
 * add, and a field that offers no substring or prefix condition cannot be
 * narrowed by the source at all — so the source is asked only for a question
 * whose answer is not already in hand.
 *
 * The answers belong to the scope they were asked under. A host that
 * narrows the view to another tenant is asking about other records, so
 * `reset` forgets them, and an answer still on its way from before is not
 * kept.
 */
export class ValueCandidateSources {
  private readonly sources = new Map<string, ValueCandidateSource | null>();
  private readonly answers = new Map<string, ValueCandidates>();
  private generation = 0;

  constructor(
    private readonly context: KernelContext,
    private readonly scope: () => FilterTree | null,
  ) {}

  /** The source of `field`, or `null` when its values are not offered. */
  of(name: string): ValueCandidateSource | null {
    if (!this.sources.has(name)) {
      const { definition, kinds } = this.context;
      const field = valueCandidateField(definition, name, kinds);
      this.sources.set(
        name,
        field
          ? {
              field: name,
              search: (query, signal) => this.search(field, query, signal),
            }
          : null,
      );
    }
    return this.sources.get(name) ?? null;
  }

  /** Forgets every answer: the scope they were asked under has changed. */
  reset(): void {
    this.generation += 1;
    this.answers.clear();
  }

  private async search(
    field: FieldDefinition,
    query: string,
    signal?: AbortSignal,
  ): Promise<ValueCandidates> {
    const { kinds } = this.context;
    const text = query.trim();
    if (!text) return this.ask(field, '', signal);
    const whole = this.answers.get(keyOf(field.name, ''));
    const narrowable = valueCandidateNarrowing(field, kinds) !== null;
    if (whole?.complete || !narrowable) {
      const all = whole ?? (await this.ask(field, '', signal));
      return {
        values: narrowValueCandidates(all.values, field, kinds, text),
        complete: all.complete,
      };
    }
    return this.ask(field, text, signal);
  }

  private async ask(
    field: FieldDefinition,
    text: string,
    signal?: AbortSignal,
  ): Promise<ValueCandidates> {
    const key = keyOf(field.name, text);
    const known = this.answers.get(key);
    if (known) return known;
    const { definition, kinds, limits, environment, source } = this.context;
    const config = withScopeFilter<AnalysisViewConfig>(
      valueCandidatesConfig(definition, field, kinds, text, limits),
      this.scope(),
    );
    // Admitted like any analysis, so a definition that cannot take the
    // question — a ceiling below one row, a scope its fields refuse — is told
    // here rather than by the service.
    const issues = validateAnalysis(definition, config, kinds, { limits });
    if (hasError(issues))
      throw new Error(
        `The values of ${field.name} cannot be asked for: ${issues
          .filter(found => found.severity === 'error')
          .map(found => found.code)
          .join(', ')}`,
      );
    const query = compileAnalysis(definition, config, kinds, {
      now: environment.now(),
      timeZone: environment.timeZone,
    });
    const generation = this.generation;
    const rows = await source.aggregate(query, undefined, abortWith(signal));
    signal?.throwIfAborted();
    const answer = readValueCandidates(definition, config, rows);
    if (generation === this.generation) this.answers.set(key, answer);
    return answer;
  }
}

function keyOf(field: string, text: string): string {
  return `${field}\u0000${text}`;
}
