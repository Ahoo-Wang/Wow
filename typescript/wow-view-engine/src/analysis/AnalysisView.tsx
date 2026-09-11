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
  Component,
  lazy,
  Suspense,
  useMemo,
  useCallback,
  useContext,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { flushSync } from 'react-dom';
import {
  PlayIcon,
  Settings2Icon,
  Maximize2Icon,
  Minimize2Icon,
  PanelLeftIcon,
  PanelLeftCloseIcon,
} from 'lucide-react';
import type { ViewEngine } from '../engine/ViewEngine.js';
import type { FilterExtensions } from '../filter/filterReactTypes.js';
import { describeConfiguredFilter } from '../filter/describeConfiguredFilter.js';
import { Button } from '../components/ui/button.js';
import { Badge } from '../components/ui/badge.js';

import { ViewRefreshControls } from '../view/ViewRefreshControls.js';
import {
  useViewExpansion,
  ViewExpansionContext,
} from '../view/viewExpansion.js';
import { ViewInstanceActions } from '../view/ViewInstanceActions.js';
import { sameJsonState } from '../lib/snapshot.js';
import { cn } from '../lib/utils.js';
import {
  analysisQueryPolicy,
  hasUnrunAnalysisQuery,
} from './analysisQueryPolicy.js';
import { AnalysisResultSummary } from './AnalysisResultSummary.js';
import { AnalysisQuerySheet } from './AnalysisQuerySheet.js';
import { AnalysisTable } from './AnalysisTable.js';
import { AnalysisResultTabs } from './AnalysisResultTabs.js';
import { AnalysisPresentationEditor } from './AnalysisPresentationEditor.js';
import { projectAnalysis } from './analysisProjection.js';
import type { AnalysisCompileContext } from './analysisModel.js';
import type { AnalysisPresentation } from './analysisPresentation.js';
import type { AnalysisExtensions } from './analysisReactTypes.js';

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
export interface AnalysisViewProps {
  engine: ViewEngine;
  extensions?: FilterExtensions & AnalysisExtensions;
  filterContext?: unknown;
  toolbarStart?: ReactNode;
  configurationOpen?: boolean;
  onConfigurationOpenChange?(open: boolean): void;
  className?: string;
}
/** Instance-local editors stay mounted for the lifetime of the containing workbench. */
export function AnalysisView(props: AnalysisViewProps) {
  const { engine } = props;
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const selected =
    state.selectedInstanceId &&
    state.sessions[state.selectedInstanceId]?.kind === 'analysis'
      ? state.selectedInstanceId
      : null;
  const [cache, setCache] = useState<{
    engine: ViewEngine;
    epoch: number;
    ids: string[];
  }>({ engine, epoch: 0, ids: [] });
  const epoch = cache.engine === engine ? cache.epoch : cache.epoch + 1;
  const ids = (cache.engine === engine ? cache.ids : []).filter(
    id => state.sessions[id]?.kind === 'analysis',
  );
  if (selected && !ids.includes(selected)) ids.push(selected);
  if (
    cache.engine !== engine ||
    ids.length !== cache.ids.length ||
    ids.some((id, index) => id !== cache.ids[index])
  )
    setCache({ engine, epoch, ids });
  return ids.map(id => (
    <AnalysisInstanceView
      {...props}
      key={`${epoch}:${id}`}
      instanceId={id}
      active={id === selected}
      toolbarStart={id === selected ? props.toolbarStart : undefined}
      configurationOpen={id === selected ? props.configurationOpen : undefined}
      onConfigurationOpenChange={
        id === selected ? props.onConfigurationOpenChange : undefined
      }
    />
  ));
}

/** Query editing and executed results share an instance, not a mutable data meaning. */
function AnalysisInstanceView({
  engine,
  instanceId,
  active,
  extensions,
  filterContext,
  toolbarStart,
  configurationOpen,
  onConfigurationOpenChange,
  className,
}: AnalysisViewProps & { instanceId: string; active: boolean }) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const containerRef = useRef<HTMLElement>(null);
  const [queryPanel, setQueryPanel] = useState<{
    id: string;
    open: boolean;
  } | null>(null);
  const [visualPanel, setVisualPanel] = useState<{
    id: string;
    open: boolean;
  } | null>(null);
  const [resultMode, setResultMode] = useState<{
    id: string;
    mode: 'analysis' | 'table';
  } | null>(null);
  const [submissionError, setSubmissionError] = useState<{
    id: string;
    message: string;
  } | null>(null);
  const [localError, setLocalError] = useState<{
    id: string;
    message: string;
  } | null>(null);
  const current = state.sessions[instanceId];
  const session = current?.kind === 'analysis' ? current : undefined;
  const id = session?.instance.id;
  const inheritedExpansion = useContext(ViewExpansionContext);
  const localExpansion = useViewExpansion(
    containerRef,
    !!session && active && !inheritedExpansion,
  );
  const expansion = inheritedExpansion ?? localExpansion;
  const autoRefresh = useCallback(async () => {
    if (id) await engine.analysis(id).refresh();
  }, [engine, id]);

  const commands = useMemo(
    () =>
      id && session?.editorEpoch !== undefined ? engine.analysis(id) : null,
    [engine, id, session?.editorEpoch],
  );
  const definition = state.definition;
  const context = useMemo<AnalysisCompileContext | null>(
    () =>
      definition?.analysis
        ? {
            fields: definition.fields,
            capability: definition.analysis,
            timeZone: definition.timeZone,
            allowedOperators: definition.allowedOperators,
            filterCompilers: engine.filterCompilers,
            compilers: engine.analysisCompilers,
          }
        : null,
    [definition, engine],
  );
  const config = session?.instance.config;
  const compiled = session?.compilation ?? null;
  if (id && queryPanel?.id !== id) setQueryPanel({ id, open: !compiled?.plan });
  const open =
    configurationOpen ??
    (queryPanel && queryPanel.id === id ? queryPanel.open : !compiled?.plan);
  const visualPreferred = visualPanel?.id === id && visualPanel?.open === true;
  const result = session?.result;
  const querying = session?.queryStatus === 'loading';
  const samePendingQuery =
    querying &&
    sameJsonState(compiled?.plan?.query, session?.pendingQuery?.query);
  const stale = session ? hasUnrunAnalysisQuery(session) : false;
  const presentation =
    config?.presentation && typeof config.presentation === 'object'
      ? config.presentation
      : tablePresentation;
  const resultPresentation = presentation;
  const resultPlan = result?.plan;
  const projectedResult = useMemo(
    () =>
      result && resultPlan
        ? projectAnalysis(resultPlan, result.rows, resultPresentation)
        : undefined,
    [result, resultPlan, resultPresentation],
  );
  const tablePlan = projectedResult?.plan;
  if (!session || !commands || !context || !definition) return null;
  const { instance } = session;
  function run(action: () => void | Promise<void>) {
    const actionId = instance.id;
    setLocalError(null);
    const failed = () => {
      const current = engine.getSnapshot().sessions[actionId];
      if (current?.queryError || current?.writeError) return;
      setLocalError({
        id: actionId,
        message:
          current?.queryError || current?.writeError || '操作失败，请重试',
      });
    };
    try {
      void Promise.resolve(action()).catch(failed);
    } catch {
      failed();
    }
  }
  function submitQuery() {
    setSubmissionError(null);
    setLocalError(null);
    try {
      const execution = commands!.start();
      void execution.completion.catch(() => {});
      if (!execution.accepted) {
        setSubmissionError({
          id: instance.id,
          message: '本次执行未被接纳；相同查询可能正在运行，请查看结果状态。',
        });
        return;
      }
      toggleConfiguration(false);
    } catch (error) {
      setSubmissionError({
        id: instance.id,
        message: error instanceof Error ? error.message : '查询配置无效',
      });
    }
  }
  function toggleConfiguration(next: boolean) {
    if (next) setSubmissionError(null);
    setQueryPanel({ id: instance.id, open: next });
    onConfigurationOpenChange?.(next);
  }
  const canRun = analysisQueryPolicy(session, 'manual');
  const runButton = (
    <Button disabled={!canRun} onClick={() => run(() => commands.run())}>
      <PlayIcon data-icon="inline-start" aria-hidden="true" />
      {samePendingQuery ? '运行中' : '运行分析'}
    </Button>
  );
  const draftFilterSummary = compiled?.plan
    ? (describeConfiguredFilter(
        instance.config.filters.root,
        definition.fields,
        definition.allowedOperators,
        engine.filterCompilers,
        definition.timeZone,
      )?.text ?? '全部记录')
    : '筛选草稿待检查';
  const mode =
    resultMode?.id === instance.id
      ? resultMode.mode
      : resultPresentation.layout === 'table'
        ? 'table'
        : 'analysis';
  const visualOpen = visualPreferred && mode === 'analysis';
  const setMode = (mode: 'analysis' | 'table') =>
    setResultMode({ id: instance.id, mode });

  const error = [
    ...new Set(
      [
        session.queryError,
        session.writeError,
        localError?.id === instance.id ? localError.message : null,
      ].filter(Boolean),
    ),
  ].join('；');
  const table =
    result && tablePlan ? (
      <AnalysisTable
        key={instance.id}
        plan={tablePlan}
        rows={result.rows}
        sort={instance.config.sort}
        stale={stale}
        querying={querying}
        sortDisabled={!session.queryValid}
        maxSort={definition.analysis?.limits?.maxSort}
        onSortChange={sort => run(() => commands.setSort(sort))}
      />
    ) : null;
  const querySummary = [
    instance.config.scope
      ? (definition.analysis?.scopes?.find(
          scope => scope.id === instance.config.scope?.id,
        )?.label ?? instance.config.scope.id)
      : '根记录',
    draftFilterSummary,
    instance.config.dimensions.length
      ? `按 ${instance.config.dimensions.map(item => item.title).join('、')} 分组`
      : '不分组',
    `统计 ${instance.config.metrics.map(item => item.title).join('、') || '未设置指标'}`,
  ].join(' · ');
  return (
    <section
      className={cn(
        'fve-root fve:[&_p]:m-0 fve:@container fve:flex fve:min-w-0 fve:flex-col fve:gap-4',
        className,
      )}
      ref={containerRef}
      aria-label="分析视图"
      data-slot="analysis-view"
      hidden={!active}
    >
      <header className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-3 fve:rounded-xl fve:border fve:bg-background fve:p-3">
        {toolbarStart ?? (
          <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
            <h1 className="fve:text-lg fve:font-semibold">{instance.title}</h1>
            <ViewInstanceActions engine={engine} session={session} run={run} />
          </div>
        )}
        <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
          <Button
            variant="outline"
            aria-expanded={visualOpen}
            onClick={() => {
              setMode('analysis');
              setVisualPanel({ id: instance.id, open: true });
            }}
          >
            <PanelLeftIcon data-icon="inline-start" aria-hidden="true" />
            可视化配置
          </Button>
          <Button
            variant="outline"
            aria-expanded={open}
            onClick={() => toggleConfiguration(!open)}
          >
            <Settings2Icon data-icon="inline-start" aria-hidden="true" />
            配置查询
          </Button>
          {active && (
            <ViewRefreshControls
              key={`refresh:${instance.id}`}
              engine={engine}
              id={instance.id}
              root={containerRef}
              querying={querying}
              pauseReason={
                session.requiresReload
                  ? '视图已变化，重新加载后恢复。'
                  : session.queryError
                    ? '查询失败，重试成功后恢复。'
                    : session.writeStatus !== 'idle'
                      ? '正在保存视图，完成后恢复。'
                      : !session.queryValid
                        ? '查询配置无效，修复后恢复。'
                        : stale || !session.filterValid
                          ? '配置尚未运行，运行或撤销修改后恢复。'
                          : !result
                            ? '运行分析后可开启自动刷新。'
                            : null
              }
              manualDisabled={
                !canRun ||
                stale ||
                !session.filterValid ||
                session.requiresReload ||
                session.writeStatus !== 'idle'
              }
              onRefresh={() => run(() => commands.run())}
              onAutoRefresh={autoRefresh}
            />
          )}
          <Button
            variant="outline"
            size="icon-sm"
            aria-label={expansion.expanded ? '收起视图' : '展开视图'}
            data-slot="view-expand"
            title={expansion.expanded ? '收起视图（Esc）' : '展开视图'}
            aria-pressed={expansion.expanded}
            onClick={expansion.toggle}
          >
            {expansion.expanded ? (
              <Minimize2Icon aria-hidden="true" />
            ) : (
              <Maximize2Icon aria-hidden="true" />
            )}
          </Button>
          {runButton}
        </div>
      </header>
      {!open && (
        <Button
          variant="outline"
          aria-label="展开查询配置"
          className="fve:h-auto fve:min-h-10 fve:min-w-0 fve:justify-between fve:gap-3 fve:text-left"
          onClick={() => toggleConfiguration(true)}
        >
          <span
            className="fve:min-w-0 fve:truncate fve:text-sm fve:font-normal"
            title={querySummary}
          >
            {querySummary}
          </span>
          <span className="fve:shrink-0 fve:text-xs">
            {!compiled?.plan || !session.filterValid ? '配置待完善 · ' : ''}
            编辑查询
          </span>
        </Button>
      )}
      <div
        className={cn(
          'fve:grid fve:min-w-0 fve:gap-4 fve:items-start',
          visualOpen && 'fve:@min-[48rem]:grid-cols-[18rem_minmax(0,1fr)]',
        )}
      >
        <section
          hidden={!visualOpen}
          aria-label="可视化配置区"
          className="fve:min-w-0 fve:rounded-xl fve:border fve:bg-background fve:p-4"
        >
          <div className="fve:mb-4 fve:flex fve:items-center fve:justify-between fve:gap-2">
            <h2 className="fve:font-semibold">可视化配置</h2>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label="折叠可视化配置"
              onClick={() => setVisualPanel({ id: instance.id, open: false })}
            >
              <PanelLeftCloseIcon />
            </Button>
          </div>
          <AnalysisPresentationEditor
            chartOnly
            showIssues={false}
            value={resultPresentation}
            plan={resultPlan}
            rows={result?.rows}
            disabled={!result}
            onChange={presentation => {
              commands.edit(config => ({ ...config, presentation }));
            }}
          />
          {!result && (
            <p className="fve:mt-3 fve:text-xs fve:text-muted-foreground">
              先运行当前查询，再配置展示方式。
            </p>
          )}
        </section>
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
                compilers={engine.filterCompilers}
              />
            )}
            {(stale || (session.queryError && result)) && (
              <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
                <p
                  role="status"
                  className="fve:text-sm fve:text-muted-foreground"
                >
                  以下仍为上次成功结果。
                </p>
                {!session.queryError && (
                  <Button
                    variant="outline"
                    disabled={!canRun}
                    onClick={() => run(() => commands.run())}
                  >
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
                <Button
                  variant="outline"
                  disabled={!canRun}
                  onClick={() => run(() => commands.run())}
                >
                  {session.queryAttempt &&
                  compiled?.plan &&
                  sameJsonState(session.queryAttempt.query, compiled.plan.query)
                    ? '重试本次查询'
                    : '运行当前配置'}
                </Button>
              )}
            </div>
          )}
          {resultPresentation.layout === 'table' &&
            !!projectedResult?.issues.length && (
              <p role="status">
                {projectedResult.issues.join('；')}
                。请选择展示方式或修复展示配置。
              </p>
            )}
          <AnalysisResultTabs
            key={instance.id}
            value={mode}
            onValueChange={setMode}
            onConfigure={() => {
              flushSync(() => setVisualPanel({ id: instance.id, open: true }));
              const panel = containerRef.current?.querySelector(
                '[aria-label="可视化配置区"]',
              );
              const target =
                panel?.querySelector<HTMLElement>(
                  '[aria-invalid="true"]:not([aria-disabled="true"]):not(:disabled)',
                ) ??
                panel?.querySelector<HTMLElement>(
                  '[data-slot="analysis-mapping-heading"]',
                );
              target?.focus();
              target?.scrollIntoView?.({ block: 'nearest' });
            }}
            table={
              table ?? (
                <p
                  role="status"
                  className="fve:min-h-64 fve:p-8 fve:text-center"
                >
                  {querying
                    ? '正在获取分析结果…'
                    : session.queryStatus === 'success'
                      ? '分析结果缓存已释放'
                      : '配置查询并运行后，在此查看聚合数据。'}
                </p>
              )
            }
            issues={
              resultPresentation.layout === 'table'
                ? []
                : projectedResult?.issues
            }
          >
            {result && resultPlan && resultPresentation.layout !== 'table' ? (
              active && (
                <ChartBoundary
                  key={`${instance.id}:${result.receivedAt}:${JSON.stringify(resultPresentation)}`}
                >
                  <Suspense
                    fallback={
                      <p role="status" className="fve:p-8 fve:text-center">
                        正在加载图表…
                      </p>
                    }
                  >
                    <Chart
                      plan={resultPlan}
                      rows={result.rows}
                      presentation={resultPresentation}
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
                      ? setVisualPanel({ id: instance.id, open: true })
                      : session.queryStatus === 'success' && canRun
                        ? run(() => commands.run())
                        : toggleConfiguration(true)
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
      </div>
      <AnalysisQuerySheet
        engine={engine}
        instanceId={instance.id}
        context={context}
        extensions={extensions}
        filterContext={filterContext}
        open={active && open}
        onOpenChange={toggleConfiguration}
        onRun={submitQuery}
        error={
          submissionError?.id === instance.id ? submissionError : undefined
        }
        filterSummary={draftFilterSummary}
      />
    </section>
  );
}
