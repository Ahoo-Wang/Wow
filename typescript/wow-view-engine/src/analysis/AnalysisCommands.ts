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

import { RequestRunner } from '../engine/RequestRunner.js';
import type {
  AnalysisViewConfig,
  AnalysisCompilerRegistry,
} from './analysisModel.js';
import {
  analysisQueryPolicy,
  hasUnrunAnalysisQuery,
  type AnalysisQueryIntent,
} from './analysisQueryPolicy.js';
import { compileScopedAnalysis } from './analysisSession.js';
import { validateAnalysisResult } from './analysisResult.js';
import type { DeepReadonly } from '../lib/types.js';
import { copy, message } from '../lib/snapshot.js';
import type { AnalysisSession } from '../contracts/viewModel.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import type { SessionStore } from '../engine/SessionStore.js';
import type { EngineScope } from '../engine/EngineScope.js';
import type { InstanceWork } from '../engine/InstanceWork.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import {
  assertConfigSize,
  validateRuntimeLimits,
  RuntimeLimitError,
  beginDiagnostic,
  type RuntimeLimits,
  type RuntimeDiagnostic,
} from '../lib/runtimeLimits.js';

/** Analysis owns plans and results; the shared engine owns all document writes. */
export class AnalysisCommands {
  private readonly requests = new Map<
    string,
    { controller: AbortController }
  >();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly compilers: AnalysisCompilerRegistry = {},
    private readonly limits: Readonly<RuntimeLimits> = validateRuntimeLimits(),
    private readonly onDiagnostic?: (event: RuntimeDiagnostic) => void,
    private readonly runner = new RequestRunner({
      maxConcurrent: limits.maxConcurrentQueries,
      maxQueued: 48,
      maxTimeoutMs: limits.queryTimeoutMs,
    }),
  ) {}

  edit(
    id: string,
    updater: (
      config: DeepReadonly<AnalysisViewConfig>,
    ) => DeepReadonly<AnalysisViewConfig>,
  ): void {
    const session = this.store.analysisSession(id);
    const config = this.scope.update(() => updater(session.instance.config));
    validateFilterJson(config);
    if (this.store.find(id) !== session)
      throw new Error('编辑回调不能重入引擎命令');
    this.store.patch(id, {
      kind: 'analysis',
      instance: { ...session.instance, config: copy(config) },
    });
  }

  private compile(config: DeepReadonly<AnalysisViewConfig>, id: string) {
    const definition = this.store.definition(id);
    return compileScopedAnalysis(
      config,
      {
        fields: definition.fields,
        capability: definition.analysis!,
        timeZone: definition.timeZone,
        allowedOperators: definition.allowedOperators,
        filterCompilers: this.store.filterCompilers,
        compilers: this.compilers,
      },
      this.store.analysisSession(id).scopeFilter,
    );
  }

  async setSort(
    id: string,
    sort: DeepReadonly<AnalysisViewConfig['sort']>,
  ): Promise<void> {
    const session = this.store.analysisSession(id);
    if (
      !analysisQueryPolicy(session, 'manual') ||
      !session.result ||
      hasUnrunAnalysisQuery(session)
    )
      return;
    const config = { ...session.instance.config, sort };
    assertConfigSize(config, this.limits.maxConfigBytes);
    const compiled = this.compile(config, id);
    if (!compiled.plan)
      throw new Error(compiled.errors.map(error => error.message).join('；'));
    this.edit(id, () => config);
    await this.run(id);
  }

  restore(id: string): void {
    const session = this.store.analysisSession(id);
    this.work.assertRestorable(session);
    this.store.patch(id, {
      kind: 'analysis',
      instance: session.baseline,
      writeError: null,
    });
  }

  cancel(id: string): void {
    const request = this.requests.get(id);
    if (!request) return;
    this.requests.delete(id);
    this.runner.cancel(`analysis:${id}`, request.controller);
    request.controller.abort();
    const session = this.store.find(id);
    if (!this.requests.has(id) && session?.kind === 'analysis')
      this.store.patch(id, {
        kind: 'analysis',
        queryStatus: session.result ? 'success' : 'idle',
        queryError: null,
        pendingQuery: null,
      });
  }
  reset(): void {
    for (const id of [...this.requests.keys()]) this.cancel(id);
  }

  refresh(id: string): Promise<void> {
    return this.run(id, 'auto');
  }

  async run(id: string, intent: AnalysisQueryIntent = 'manual'): Promise<void> {
    await this.start(id, intent).completion;
  }

  private publishRejected(
    id: string,
    session: AnalysisSession,
    error: unknown,
  ): void {
    if (this.requests.has(id) || this.store.find(id) !== session) return;
    this.store.patch(id, {
      kind: 'analysis',
      queryStatus: 'error',
      queryError: message(error),
    });
  }

  start(
    id: string,
    intent: AnalysisQueryIntent = 'manual',
  ): { accepted: boolean; completion: Promise<void> } {
    const refused = () => ({ accepted: false, completion: Promise.resolve() });
    const session = this.store.analysisSession(id);
    if (intent !== 'manual' && !analysisQueryPolicy(session, intent))
      return refused();
    const diagnostic = beginDiagnostic(this.onDiagnostic, 'analysis', 'query');
    const controller = new AbortController();
    try {
      assertConfigSize(session.instance.config, this.limits.maxConfigBytes);
      if (!session.filterValid) throw new Error('筛选输入无效');
    } catch (error) {
      this.publishRejected(id, session, error);
      diagnostic(
        'failed',
        error instanceof RuntimeLimitError ? error.code : 'INVALID_CONFIG',
      );
      throw error;
    }
    const definition = this.store.definition(id);
    const compiled = this.compile(session.instance.config, id);
    if (!compiled.plan) {
      const error = new Error(
        compiled.errors.map(value => value.message).join('；'),
      );
      this.publishRejected(id, session, error);
      diagnostic('failed', 'INVALID_CONFIG');
      throw error;
    }
    if (!analysisQueryPolicy({ ...session, compilation: compiled }, intent))
      return refused();
    const plan = copy(compiled.plan),
      config = copy(session.instance.config);
    const lifecycle = this.scope.version;
    const generation = this.store.generation(id);
    const request = { controller };
    const previous = this.requests.get(id);
    this.requests.set(id, request);
    const current = () =>
      this.scope.current(lifecycle) &&
      this.store.generation(id) === generation &&
      this.requests.get(id) === request &&
      this.store.find(id)?.kind === 'analysis';
    let accepted = false;
    let reading: Promise<unknown>;
    try {
      reading = this.runner.submit({
        key: `analysis:${id}`,
        policy: session.queryPolicy ?? 'reject',
        timeoutMs: this.limits.queryTimeoutMs,
        controller,
        onAccepted: waiting => {
          accepted = true;
          if (waiting) diagnostic('queued');
          if (waiting && current())
            this.store.patch(id, {
              kind: 'analysis',
              queryStatus: 'waiting',
              queryError: null,
              pendingQuery: plan,
              queryAttempt: plan,
            });
        },
        run: async () => {
          if (!current())
            throw new RuntimeLimitError('CANCELLED', '操作已取消');
          this.store.patch(id, {
            kind: 'analysis',
            queryStatus: 'loading',
            queryError: null,
            pendingQuery: plan,
            queryAttempt: plan,
          });
          accepted = true;
          diagnostic('started');
          if (!current())
            throw new RuntimeLimitError('CANCELLED', '操作已取消');
          if (!definition.sourceId) throw new Error('查询定义缺少数据源');
          const positionSource = this.store.source(id);
          const source =
            typeof positionSource === 'function'
              ? await positionSource(controller)
              : (positionSource ??
                (await this.host.resolveSource(definition.sourceId)));
          if (!current() || controller.signal.aborted)
            throw new RuntimeLimitError('CANCELLED', '操作已取消');
          if (!source.aggregate)
            throw new Error('数据源不支持 aggregate 分析查询');
          return source.aggregate(copy(plan.query), undefined, controller);
        },
      }).completion;
    } catch (error) {
      if (this.requests.get(id) === request) {
        if (previous) this.requests.set(id, previous);
        else this.requests.delete(id);
      }
      this.publishRejected(id, session, error);
      diagnostic(
        'failed',
        error instanceof RuntimeLimitError ? error.code : 'QUERY_FAILED',
      );
      throw error;
    }
    const completion = (async () => {
      try {
        const rows = await reading;
        if (!current()) {
          diagnostic('superseded');
          return;
        }
        const result = validateAnalysisResult(rows, plan);
        if (!result.rows)
          throw new Error(result.errors.map(value => value.message).join('；'));
        this.store.patch(id, {
          kind: 'analysis',
          queryStatus: 'success',
          queryError: null,
          pendingQuery: null,
          result: {
            plan,
            config,
            rows: copy(result.rows),
            receivedAt: Date.now(),
          },
        });
        if (this.requests.get(id) === request) this.requests.delete(id);
        diagnostic('succeeded');
      } catch (error) {
        if (!current()) {
          diagnostic(this.requests.has(id) ? 'superseded' : 'cancelled');
          return;
        }
        this.requests.delete(id);
        this.store.patch(id, {
          kind: 'analysis',
          queryStatus: 'error',
          pendingQuery: null,
          queryError:
            error instanceof RuntimeLimitError
              ? error.message
              : '分析查询失败，请重试',
        });
        diagnostic(
          'failed',
          error instanceof RuntimeLimitError ? error.code : 'QUERY_FAILED',
        );
        throw error;
      }
    })();
    return { accepted, completion };
  }
}
