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

import { sameJsonState } from '../lib/snapshot.js';
import type { AnalysisViewConfig } from './analysisModel.js';
import type { DeepReadonly } from '../lib/types.js';
import type { AnalysisSession } from '../contracts/viewModel.js';

export type AnalysisQueryIntent = 'manual' | 'open' | 'reload' | 'auto';

export function analysisQueryPolicy(
  session: AnalysisSession,
  intent: AnalysisQueryIntent,
): boolean {
  if (!session.queryValid || !session.compilation.plan) return false;
  if (
    (session.queryStatus === 'loading' || session.queryStatus === 'waiting') &&
    sameJsonState(session.pendingQuery?.query, session.compilation.plan.query)
  )
    return false;
  if (intent === 'manual') return true;
  if (session.conflict || session.requiresReload) return false;
  if (intent === 'reload') return !session.dirty;
  if (intent === 'open') return true;
  return (
    session.queryStatus === 'success' &&
    session.writeStatus === 'idle' &&
    !!session.result &&
    sameJsonState(session.compilation.plan.query, session.result.plan.query)
  );
}

export function hasUnrunAnalysisQuery(
  session: Pick<AnalysisSession, 'result' | 'compilation'>,
): boolean {
  return (
    !!session.result &&
    (!session.compilation.plan ||
      !sameJsonState(session.compilation.plan.query, session.result.plan.query))
  );
}

export function sameAnalysisQueryDraft(
  a: DeepReadonly<AnalysisViewConfig>,
  b: DeepReadonly<AnalysisViewConfig>,
): boolean {
  const { presentation: _a, ...queryA } = a,
    { presentation: _b, ...queryB } = b;
  void _a;
  void _b;
  return sameJsonState(queryA, queryB);
}
