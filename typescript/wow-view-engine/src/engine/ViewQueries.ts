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

import { analysisQueryPolicy } from '../analysis/analysisQueryPolicy.js';
import type { RecordQueries } from '../record/engine/RecordQueries.js';
import type { AnalysisCommands } from '../analysis/AnalysisCommands.js';
import type { SessionStore } from './SessionStore.js';
import type { EngineScope } from './EngineScope.js';

/** Shared navigation owns dispatch; neither record nor analysis depends on its sibling. */
export class ViewQueries {
  private readonly opened = new Map<string, number>();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly record: RecordQueries,
    private readonly analysis: AnalysisCommands,
  ) {}
  reset(): void {
    this.opened.clear();
    this.record.reset();
    this.analysis.reset();
  }
  cancel(id: string): void {
    const session = this.store.find(id);
    if (
      session?.kind === 'analysis' &&
      session.queryStatus === 'loading' &&
      !session.result
    )
      this.opened.delete(id);
    this.record.cancel(id);
    this.analysis.cancel(id);
  }
  change(
    id: string,
    update: () => void,
    invalidateSummary = false,
    mode: 'query' | 'refresh' | 'scope' = 'query',
  ): Promise<void> {
    return this.record.change(id, update, invalidateSummary, mode);
  }
  followUp(id: string, refresh = false): () => Promise<void> {
    const record = this.record.followUp(id, refresh),
      version = this.scope.version,
      selection = this.scope.selection;
    return async () => {
      if (
        !this.scope.current(version) ||
        this.scope.selection !== selection ||
        this.store.getSnapshot().selectedInstanceId !== id
      )
        return;
      const session = this.store.find(id);
      if (!session) return;
      if (session.kind === 'record') {
        await record();
        return;
      }
      // Revisiting a failed query is not an implicit retry, even if its first run was manual.
      if (!refresh && session.queryStatus === 'error') return;
      const intent = refresh ? 'reload' : 'open';
      if (!analysisQueryPolicy(session, intent)) return;
      const generation = this.store.generation(id);
      if (!refresh && this.opened.get(id) === generation) return;
      const execution = this.analysis.start(id, intent);
      if (execution.accepted && this.store.find(id)?.queryStatus !== 'idle')
        this.opened.set(id, generation);
      await execution.completion;
    };
  }
}
