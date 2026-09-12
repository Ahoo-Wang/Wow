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
import { Component, lazy, Suspense, useMemo, type ReactNode } from 'react';
import type {
  AnalysisSession,
  ViewDefinition,
} from '../contracts/viewModel.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { sameJsonState } from '../lib/snapshot.js';
import { Badge } from '../components/ui/badge.js';
import { Button } from '../components/ui/button.js';
import { AnalysisResultSummary } from './AnalysisResultSummary.js';
import { AnalysisResultTabs } from './AnalysisResultTabs.js';
import { AnalysisTable } from './AnalysisTable.js';
import type { AnalysisViewConfig } from './analysisModel.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
import { projectAnalysis } from './analysisProjection.js';
import { hasUnrunAnalysisQuery } from './analysisQueryPolicy.js';

const Chart = lazy(() =>
  import('./AnalysisChart.js').then(module => ({
    default: module.AnalysisChart,
  })),
);

class ChartBoundary extends Component<
  { children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <p role="alert">图表暂时无法显示，请切换到下方“数据表”查看结果。</p>
    ) : (
      this.props.children
    );
  }
}

const tablePresentation: AnalysisPresentation = {
  layout: 'table',
  columns: [],
};

interface AnalysisResultProps {
  active: boolean;
  session: DeepReadonly<AnalysisSession>;
  definition: DeepReadonly<ViewDefinition>;
  compilers: FilterCompilerRegistry;
  mode: 'analysis' | 'table';
  canRun: boolean;
  localError?: string;
  onRun(): void;
  onSortChange(sort: AnalysisViewConfig['sort']): void;
  onModeChange(mode: 'analysis' | 'table'): void;
  onConfigure(): void;
  onChoosePresentation(): void;
  onOpenQuery(): void;
}

/** Executed-result projection and inspection never subscribe to the engine. */
export function AnalysisResult({
  active,
  session,
  definition,
  compilers,
  mode,
  canRun,
  localError,
  onRun,
  onSortChange,
  onModeChange,
  onConfigure,
  onChoosePresentation,
  onOpenQuery,
}: AnalysisResultProps) {
  const { instance, result } = session;
  const querying = session.queryStatus === 'loading';
  const stale = hasUnrunAnalysisQuery(session);
  const presentation =
    instance.config.presentation &&
    typeof instance.config.presentation === 'object'
      ? instance.config.presentation
      : tablePresentation;
  const plan = result?.plan;
  const projected = useMemo(
    () =>
      result && plan
        ? projectAnalysis(plan, result.rows, presentation)
        : undefined,
    [result, plan, presentation],
  );
  const error = [
    ...new Set(
      [session.queryError, session.writeError, localError].filter(Boolean),
    ),
  ].join('；');
  const table =
    result && projected?.plan ? (
      <AnalysisTable
        key={instance.id}
        plan={projected.plan}
        rows={result.rows}
        sort={instance.config.sort}
        stale={stale}
        querying={querying}
        sortDisabled={!session.queryValid}
        maxSort={definition.analysis?.limits?.maxSort}
        onSortChange={onSortChange}
      />
    ) : null;

  return (
    <div
      aria-label="分析结果区"
      className="fve:flex fve:min-w-0 fve:flex-col fve:gap-4 fve:rounded-xl fve:border fve:bg-background fve:p-4"
    >
      <div className="fve:flex fve:flex-col fve:gap-3">
        <div className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2">
          <h2 className="fve:font-semibold">分析结果</h2>
          <div className="fve:flex fve:items-center fve:gap-2">
            {querying && <Badge variant="secondary">正在查询</Badge>}
            {stale && <Badge variant="outline">配置尚未运行</Badge>}
            {!session.queryValid && (
              <Badge variant="outline">查询配置待修复</Badge>
            )}
          </div>
        </div>
        {result && (
          <AnalysisResultSummary
            result={result}
            definition={definition}
            compilers={compilers}
          />
        )}
        {(stale || (session.queryError && result)) && (
          <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
            <p role="status" className="fve:text-sm fve:text-muted-foreground">
              以下仍为上次成功结果。
            </p>
            {!session.queryError && (
              <Button variant="outline" disabled={!canRun} onClick={onRun}>
                运行当前配置
              </Button>
            )}
          </div>
        )}
      </div>
      {error && (
        <div
          role="alert"
          className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2 fve:rounded-md fve:border fve:border-destructive/30 fve:p-3 fve:text-destructive"
        >
          <span>{error}</span>
          {session.queryError && (
            <Button variant="outline" disabled={!canRun} onClick={onRun}>
              {session.queryAttempt &&
              session.compilation.plan &&
              sameJsonState(
                session.queryAttempt.query,
                session.compilation.plan.query,
              )
                ? '重试本次查询'
                : '运行当前配置'}
            </Button>
          )}
        </div>
      )}
      {presentation.layout === 'table' && !!projected?.issues.length && (
        <p role="status">
          {projected.issues.join('；')}。请选择展示方式或修复展示配置。
        </p>
      )}
      <AnalysisResultTabs
        key={instance.id}
        value={mode}
        onValueChange={onModeChange}
        onConfigure={onConfigure}
        table={
          table ?? (
            <p role="status" className="fve:min-h-64 fve:p-8 fve:text-center">
              {querying
                ? '正在获取分析结果…'
                : session.queryStatus === 'success'
                  ? '分析结果缓存已释放'
                  : '配置查询并运行后，在此查看聚合数据。'}
            </p>
          )
        }
        issues={presentation.layout === 'table' ? [] : projected?.issues}
      >
        {result && plan && presentation.layout !== 'table' ? (
          active && (
            <ChartBoundary
              key={`${instance.id}:${result.receivedAt}:${JSON.stringify(presentation)}`}
            >
              <Suspense
                fallback={
                  <p role="status" className="fve:p-8 fve:text-center">
                    正在加载图表…
                  </p>
                }
              >
                <Chart
                  plan={plan}
                  rows={result.rows}
                  presentation={presentation}
                />
              </Suspense>
            </ChartBoundary>
          )
        ) : (
          <div
            role="status"
            className="fve:flex fve:min-h-64 fve:flex-col fve:items-center fve:justify-center fve:gap-3 fve:text-center"
          >
            <p>
              {!result
                ? querying
                  ? '正在获取分析结果…'
                  : session.queryStatus === 'success'
                    ? '分析结果缓存已释放'
                    : '先配置查询并运行'
                : '先选择报表展示方式'}
            </p>
            <Button
              variant="outline"
              onClick={() =>
                result
                  ? onChoosePresentation()
                  : session.queryStatus === 'success' && canRun
                    ? onRun()
                    : onOpenQuery()
              }
            >
              {result
                ? '选择展示方式'
                : session.queryStatus === 'success' && canRun
                  ? '重新运行查询'
                  : '配置查询'}
            </Button>
          </div>
        )}
      </AnalysisResultTabs>
    </div>
  );
}
