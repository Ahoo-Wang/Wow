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
  AnalysisCompilerRegistry,
} from './analysisModel.js';
import {
  analysisQueryPolicy,
  hasUnrunAnalysisQuery,
  type AnalysisQueryIntent,
} from './analysisQueryPolicy.js';
import { compileAnalysis } from './analysisCompiler.js';
import { validateAnalysisResult } from './analysisResult.js';
import type { DeepReadonly } from '../lib/types.js';
import { copy } from '../lib/snapshot.js';
import { validateFilterJson } from '../filter/filterConfigurationValidation.js';
import type { SessionStore } from '../engine/SessionStore.js';
import type { EngineScope } from '../engine/EngineScope.js';
import type { InstanceWork } from '../engine/InstanceWork.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import {
  assertConfigSize,
  validateRuntimeLimits,
  withDeadline,
  QueryBudget,
  RuntimeLimitError,
  reportDiagnostic,
  type RuntimeLimits,
  type RuntimeDiagnostic,
} from '../lib/runtimeLimits.js';

/** Analysis owns plans and results; the shared engine owns all document writes. */
export class AnalysisCommands {
  private readonly requests = new Map<
    string,
    { controller: AbortController; release: () => void }
  >();
  constructor(
    private readonly store: SessionStore,
    private readonly scope: EngineScope,
    private readonly host: ViewHost,
    private readonly work: InstanceWork,
    private readonly compilers: AnalysisCompilerRegistry = {},
    private readonly limits: Readonly<RuntimeLimits> = validateRuntimeLimits(),
    private readonly onDiagnostic?: (event: RuntimeDiagnostic) => void,
    private readonly budget = new QueryBudget(limits.maxConcurrentQueries),
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

  private compile(config: DeepReadonly<AnalysisViewConfig>) {
    const definition = this.store.definition();
    return compileAnalysis(config, {
      fields: definition.fields,
      capability: definition.analysis!,
      timeZone: definition.timeZone,
      allowedOperators: definition.allowedOperators,
      filterCompilers: this.store.filterCompilers,
      compilers: this.compilers,
    });
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
    const compiled = this.compile(config);
    if (!compiled.plan)
      throw new Error(compiled.errors.map(error => error.message).join('；'));
    this.edit(id, () => config);
    await this.run(id);
  }

  restore(id: string): void {
    const session = this.store.analysisSession(id);
    this.work.assertWritable(session);
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
    request.release();
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

  start(
    id: string,
    intent: AnalysisQueryIntent = 'manual',
  ): { accepted: boolean; completion: Promise<void> } {
    const refused = () => ({ accepted: false, completion: Promise.resolve() });
    const session = this.store.analysisSession(id);
    if (intent !== 'manual' && !analysisQueryPolicy(session, intent))
      return refused();
    const started = performance.now();
    const operationId = crypto.randomUUID();
    const diagnostic = (
      phase: RuntimeDiagnostic['phase'],
      errorCode?: string,
    ) =>
      reportDiagnostic(this.onDiagnostic, {
        operationId,
        kind: 'analysis',
        operation: 'query',
        phase,
        elapsedMs: performance.now() - started,
        ...(errorCode ? { errorCode } : {}),
      });
    let release: () => void;
    const controller = new AbortController();
    try {
      assertConfigSize(session.instance.config, this.limits.maxConfigBytes);
      if (!session.filterValid) throw new Error('筛选输入无效');
    } catch (error) {
      diagnostic(
        'failed',
        error instanceof RuntimeLimitError ? error.code : 'INVALID_CONFIG',
      );
      throw error;
    }
    const definition = this.store.definition();
    const compiled = this.compile(session.instance.config);
    if (!compiled.plan) {
      diagnostic('failed', 'INVALID_CONFIG');
      throw new Error(compiled.errors.map(value => value.message).join('；'));
    }
    if (!analysisQueryPolicy({ ...session, compilation: compiled }, intent))
      return refused();
    try {
      release = this.budget.acquire(`analysis:${id}`, controller);
    } catch (error) {
      diagnostic(
        'failed',
        error instanceof RuntimeLimitError ? error.code : 'BUSY',
      );
      throw error;
    }
    const plan = copy(compiled.plan),
      config = copy(session.instance.config);
    const lifecycle = this.scope.version;
    const generation = this.store.generation(id);
    const request = { controller, release };
    const previous = this.requests.get(id);
    this.requests.set(id, request);
    const current = () =>
      this.scope.current(lifecycle) &&
      this.store.generation(id) === generation &&
      this.requests.get(id) === request &&
      this.store.find(id)?.kind === 'analysis';
    previous?.controller.abort();
    let accepted = false;
    const completion = (async () => {
      try {
        if (!current()) {
          diagnostic('superseded');
          return;
        }
        this.store.patch(id, {
          kind: 'analysis',
          queryStatus: 'loading',
          queryError: null,
          pendingQuery: plan,
          queryAttempt: plan,
        });
        accepted = true;
        diagnostic('started');
        const rows = await withDeadline(
          async () => {
            if (!current())
              throw new RuntimeLimitError('CANCELLED', '操作已取消');
            const source = await this.host.resolveSource(definition.sourceId);
            if (!current() || controller.signal.aborted)
              throw new RuntimeLimitError('CANCELLED', '操作已取消');
            if (!source.aggregate)
              throw new Error('数据源不支持 aggregate 分析查询');
            return source.aggregate(copy(plan.query), undefined, controller);
          },
          this.limits.queryTimeoutMs,
          controller,
        );
        if (!current()) {
          diagnostic('superseded');
          return;
        }
        const result = validateAnalysisResult(rows, plan);
        if (!result.rows)
          throw new Error(result.errors.map(value => value.message).join('；'));
        this.requests.delete(id);
        release();
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
        diagnostic('succeeded');
      } catch (error) {
        if (!current()) {
          diagnostic(this.requests.has(id) ? 'superseded' : 'cancelled');
          return;
        }
        this.requests.delete(id);
        release();
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
      } finally {
        release();
      }
    })();
    return { accepted, completion };
  }
}
