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
import { useRef, useState, lazy, Suspense } from 'react';
import { ErrorBoundary } from 'react-error-boundary';
import { RenderCommit } from '../lib/RenderCommit.js';
import { Button } from '../components/ui/button.js';
import type { DashboardLayoutProps } from './DashboardGrid.js';

const loadLayout = () =>
  lazy(() =>
    import('./DashboardGrid.js').then(module => ({
      default: module.DashboardLayout,
    })),
  );

/** Keep data and saved/draft state available even when the optional grid cannot load. */
export function DashboardLayoutBoundary(
  props: DashboardLayoutProps & {
    onAvailabilityChange(available: boolean): void;
  },
) {
  const [Layout, setLayout] = useState(loadLayout);
  const region = useRef<HTMLDivElement>(null);
  const { onAvailabilityChange, ...layout } = props;
  return (
    <div
      ref={region}
      className="fve:flex fve:min-w-0 fve:flex-col fve:gap-4"
      tabIndex={-1}
      aria-label="仪表盘面板"
      data-dashboard-layout-region=""
    >
      <ErrorBoundary
        onError={() => onAvailabilityChange(false)}
        fallbackRender={({ resetErrorBoundary }) => (
          <>
            <div
              role="alert"
              className="fve:flex fve:flex-col fve:items-start fve:gap-2 fve:rounded-md fve:border fve:p-3"
            >
              <p>
                交互布局暂不可用，已切换为简洁布局。可以继续查看面板和保存配置。
              </p>
              <Button
                variant="outline"
                onClick={() => {
                  setLayout(loadLayout);
                  resetErrorBoundary();
                  region.current?.focus();
                }}
              >
                重试布局
              </Button>
              <p className="fve:text-xs fve:text-muted-foreground">
                若重试仍失败，请先保存配置，再刷新页面。
              </p>
            </div>
            <div className="fve:grid fve:gap-4" data-dashboard-fallback="">
              {[...props.panels]
                .sort(
                  (a, b) => a.layout.y - b.layout.y || a.layout.x - b.layout.x,
                )
                .map((panel, index) => (
                  <section
                    key={panel.id}
                    data-dashboard-panel={panel.id}
                    className="fve-dashboard-panel fve:min-w-0 fve:rounded-xl fve:border fve:bg-card fve:text-card-foreground fve:shadow-sm"
                    aria-label={`面板 ${index + 1}：${props.title(panel.id)}`}
                  >
                    {props.children(panel)}
                  </section>
                ))}
            </div>
          </>
        )}
      >
        <RenderCommit onCommit={() => onAvailabilityChange(true)}>
          <Suspense fallback={<p role="status">正在加载仪表盘布局…</p>}>
            <Layout {...layout} />
          </Suspense>
        </RenderCommit>
      </ErrorBoundary>
    </div>
  );
}
