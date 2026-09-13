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
import { Component, useRef, useState, type ReactNode } from 'react';
import {
  RefreshCwIcon,
  MoreHorizontalIcon,
  InfoIcon,
  ExternalLinkIcon,
} from 'lucide-react';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '../components/ui/dropdown-menu.js';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '../components/ui/dialog.js';
import { AnalysisResultSummary } from '../analysis/AnalysisResultSummary.js';
import {
  DataViewContent,
  useDataViewSession,
} from '../view/DataViewContent.js';
import { Button } from '../components/ui/button.js';
import { describeFilter } from '../filter/filterSummary.js';
import type { FilterCompilerRegistry } from '../filter/filterModel.js';
import type { ViewExtensions } from '../view/viewReactTypes.js';
import type { DashboardPanelSnapshot } from './DashboardRuntime.js';

export class DashboardPanelBoundary extends Component<
  { children: ReactNode },
  { error: boolean }
> {
  state = { error: false };
  static getDerivedStateFromError() {
    return { error: true };
  }
  render() {
    return this.state.error ? (
      <div role="alert" className="fve:p-4">
        <p>面板内容无法显示。</p>
        <Button
          variant="outline"
          onClick={() => this.setState({ error: false })}
        >
          重试显示面板
        </Button>
      </div>
    ) : (
      this.props.children
    );
  }
}
export function DashboardPanelContent({
  panel,
  positionLabel,
  filterDetails,
  filterCount = 0,
  extensions,
  compilers,
  onRefresh,
  onReload,
  onRepair,
  onOpenOriginal,
}: {
  panel: DashboardPanelSnapshot;
  positionLabel?: string;
  filterDetails?: ReactNode;
  filterCount?: number;
  extensions?: ViewExtensions;
  compilers: FilterCompilerRegistry;
  onRefresh(): void | Promise<void>;
  onReload(): void | Promise<void>;
  onRepair?(): void;
  onOpenOriginal?(): void | Promise<void>;
}) {
  const menuRef = useRef<HTMLButtonElement>(null);
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  function run(action: () => void | Promise<void>) {
    setLocalError(null);
    try {
      void Promise.resolve(action()).catch(error =>
        setLocalError(error instanceof Error ? error.message : '操作失败'),
      );
    } catch (error) {
      setLocalError(error instanceof Error ? error.message : '操作失败');
    }
  }
  const position = panel.position;
  const session = useDataViewSession(position);
  const definition = panel.definition;
  const title = panel.instance?.title ?? '视图面板';
  const querying = panel.loading;
  const resultFilter =
    session?.kind === 'record'
      ? session.result?.filter
      : session?.result?.plan.query.filter;
  return (
    <>
      <header className="fve:flex fve:items-center fve:justify-between fve:gap-2 fve:px-4 fve:py-2">
        <h2
          className="fve:min-w-0 fve:flex-1 fve:truncate fve:font-semibold"
          title={title}
        >
          {title}
        </h2>
        <div className="fve:flex fve:shrink-0 fve:items-center fve:gap-1">
          {filterCount > 0 && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDetailsOpen(true)}
            >
              筛选 {filterCount}
            </Button>
          )}
          {position && (
            <Button
              variant="ghost"
              size="icon-sm"
              disabled={querying}
              onClick={() => run(onRefresh)}
              aria-label={`刷新${title}`}
              title="刷新"
            >
              <RefreshCwIcon aria-hidden="true" />
            </Button>
          )}
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <Button
                  variant="ghost"
                  size="icon-sm"
                  ref={menuRef}
                  aria-label={`${title}面板选项`}
                  title="面板选项"
                />
              }
            >
              <MoreHorizontalIcon aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent
              align="end"
              finalFocus={!detailsOpen}
              className="fve:min-w-40"
            >
              <DropdownMenuItem onClick={() => setDetailsOpen(true)}>
                <InfoIcon aria-hidden="true" />
                数据详情
              </DropdownMenuItem>
              {onOpenOriginal && panel.instance && (
                <DropdownMenuItem onClick={() => run(onOpenOriginal)}>
                  <ExternalLinkIcon aria-hidden="true" />
                  编辑原视图
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      <Dialog open={detailsOpen} onOpenChange={setDetailsOpen}>
        <DialogContent
          finalFocus={menuRef}
          className="fve:max-h-[80dvh] fve:overflow-y-auto"
        >
          <DialogHeader>
            <DialogTitle>{title} · 数据详情</DialogTitle>
            <DialogDescription>
              查看当前结果的数据来源和实际查询口径。
            </DialogDescription>
          </DialogHeader>
          <div className="fve:flex fve:flex-col fve:gap-3 fve:text-sm">
            {definition && <p>数据来源：{definition.title}</p>}
            {session?.kind === 'analysis' && session.result && definition ? (
              <AnalysisResultSummary
                result={session.result}
                definition={definition}
                compilers={compilers}
              />
            ) : (
              <>
                {session?.result && (
                  <p>
                    本地接收：
                    {new Date(session.result.receivedAt).toLocaleString(
                      'zh-CN',
                    )}
                  </p>
                )}
              </>
            )}
            {resultFilter && definition && (
              <p>
                结果筛选口径：
                {describeFilter(resultFilter, definition.fields).text}
              </p>
            )}
            {filterDetails}
          </div>
        </DialogContent>
      </Dialog>
      {panel.status === 'loading' && (
        <p role="status" className="fve:p-4">
          正在加载视图引用…
        </p>
      )}
      {(panel.error || localError) && (
        <div
          role="alert"
          className="fve:m-4 fve:rounded-md fve:border fve:border-destructive/40 fve:p-3"
        >
          <p>{panel.error ?? localError}</p>
          <div className="fve:mt-2 fve:flex fve:flex-wrap fve:gap-2">
            {panel.blocked && onRepair && (
              <Button variant="outline" size="sm" onClick={onRepair}>
                修复绑定
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={() => run(onReload)}>
              重新加载引用
            </Button>
          </div>
        </div>
      )}
      {!panel.error && !panel.loading && !position && (
        <p role="status" className="fve:p-4">
          面板待配置，请完成筛选绑定后查询。
        </p>
      )}
      {position && definition && (
        <DataViewContent
          key={position.identity.id}
          position={position}
          definition={definition}
          extensions={extensions}
          compilers={compilers}
          label={positionLabel ?? title}
        />
      )}
    </>
  );
}
