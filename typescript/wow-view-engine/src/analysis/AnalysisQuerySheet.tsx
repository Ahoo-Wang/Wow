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
  useState,
  useEffect,
  useMemo,
  useRef,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import { ChevronDownIcon } from 'lucide-react';
import type { ViewEngine } from '../engine/ViewEngine.js';
import type { AnalysisCompileContext } from './analysisModel.js';
import type { FilterExtensions } from '../filter/filterReactTypes.js';
import type { AnalysisExtensions } from './analysisReactTypes.js';
import { FilterPanel } from '../filter/FilterPanel.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import { Button } from '../components/ui/button.js';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '../components/ui/dialog.js';
import { OverlayScope } from '../lib/OverlayScope.js';
import { AnalysisEditor } from './AnalysisEditor.js';
/** One instance-local validity gate: a valid root cannot overwrite an invalid element. */
function AnalysisFilterValidity({
  scopeKey,
  onChange,
  children,
}: {
  scopeKey: string;
  onChange(valid: boolean): void;
  children(
    root: (valid: boolean) => void,
    scope: (valid: boolean) => void,
  ): ReactNode;
}) {
  const [root, setRoot] = useState(true);
  const [scopes, setScopes] = useState(() => new Map<string, boolean>());
  const valid = root && (scopes.get(scopeKey) ?? true);
  useEffect(() => {
    onChange(valid);
  }, [valid, onChange]);
  return children(setRoot, valid =>
    setScopes(previous =>
      previous.get(scopeKey) === valid
        ? previous
        : new Map(previous).set(scopeKey, valid),
    ),
  );
}

/** Own editor subscriptions separately from result and dialog presentation. */
function AnalysisQueryEditor({
  engine,
  context,
  extensions,
  filterContext,
  visible,
  filterSummary,
  instanceId,
}: {
  engine: ViewEngine;
  extensions?: FilterExtensions & AnalysisExtensions;
  filterContext?: unknown;
  instanceId: string;
} & {
  context: AnalysisCompileContext;
  visible: boolean;
  filterSummary: string;
}) {
  const state = useSyncExternalStore(
    engine.subscribe,
    engine.getSnapshot,
    engine.getSnapshot,
  );
  const selected = state.sessions[instanceId];
  const session = selected?.kind === 'analysis' ? selected : undefined;
  const id = session?.instance.id;
  const commands = useMemo(
    () =>
      id && session?.editorEpoch !== undefined ? engine.analysis(id) : null,
    [engine, id, session?.editorEpoch],
  );
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [filtersVisited, setFiltersVisited] = useState(false);
  if (!filtersVisited && (filtersOpen || session?.filterValid === false))
    setFiltersVisited(true);
  const definition = state.definition;
  if (!session || !definition || !commands) return null;
  const { instance } = session;
  return (
    <AnalysisFilterValidity
      key={`${instance.id}:${session.editorEpoch}`}
      scopeKey={instance.config.scope?.id ?? 'root'}
      onChange={commands.setFilterValidity}
    >
      {(rootValidity, scopeValidity) => (
        <div className="fve:[&_p]:m-0 fve:flex fve:min-w-0 fve:flex-col fve:gap-3">
          <details
            className="fve:group fve:rounded-lg fve:border"
            open={filtersOpen || session.filterValid === false}
          >
            <summary
              className="fve:flex fve:cursor-pointer fve:list-none fve:items-center fve:justify-between fve:gap-2 fve:px-3 fve:py-2 fve:text-sm fve:focus-visible:ring-2 fve:focus-visible:ring-ring fve:[&::-webkit-details-marker]:hidden"
              onClick={event => {
                event.preventDefault();
                setFiltersVisited(true);
                setFiltersOpen(!filtersOpen);
              }}
            >
              <span className="fve:flex fve:min-w-0 fve:items-center fve:gap-3">
                <span className="fve:font-medium">筛选条件</span>
                <span
                  className="fve:truncate fve:text-xs fve:text-muted-foreground"
                  title={filterSummary}
                >
                  {session.filterValid === false ? '条件待完善' : filterSummary}
                </span>
              </span>
              <ChevronDownIcon
                aria-hidden="true"
                className="fve:size-4 fve:shrink-0 fve:group-open:rotate-180"
              />
            </summary>
            <div
              className="fve:border-t fve:p-3"
              onFocus={() => setFiltersOpen(true)}
            >
              {(filtersVisited || filtersOpen || !session.filterValid) && (
                <FilterPanel
                  renderToolbar={toolbar => (
                    <FilterSelect
                      label="筛选模式"
                      value={toolbar.mode}
                      options={toolbar.options}
                      disabled={toolbar.disabled}
                      onValueChange={toolbar.onModeChange}
                    />
                  )}
                  value={instance.config.filters}
                  appliedValue={
                    (session.result?.config ?? session.baseline.config).filters
                  }
                  fields={definition.fields}
                  timeZone={definition.timeZone}
                  allowedOperators={definition.allowedOperators}
                  editors={definition.filterEditors}
                  extensions={extensions}
                  context={filterContext}
                  showQueryAction={false}
                  onApply={() => commands.run()}
                  onChange={filters =>
                    commands.edit(config => ({ ...config, filters }))
                  }
                  onValidityChange={rootValidity}
                />
              )}
            </div>
          </details>
          <AnalysisEditor
            key={instance.id}
            value={instance.config}
            appliedValue={session.result?.config ?? session.baseline.config}
            context={context}
            errors={session.validation}
            visible={visible}
            extensions={extensions}
            filterContext={filterContext}
            onFilterValidityChange={scopeValidity}
            onChange={next => commands.edit(() => next)}
          />
        </div>
      )}
    </AnalysisFilterValidity>
  );
}

export function AnalysisQuerySheet(props: {
  engine: ViewEngine;
  instanceId: string;
  context: AnalysisCompileContext;
  extensions?: FilterExtensions & AnalysisExtensions;
  filterContext?: unknown;
  open: boolean;
  onOpenChange(open: boolean): void;
  onRun(): void;
  error?: { message: string };
  filterSummary: string;
}) {
  const body = useRef<HTMLDivElement>(null),
    alert = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (!props.open || !props.error) return;
    const target =
      body.current?.querySelector<HTMLElement>(
        'input[aria-invalid="true"],textarea[aria-invalid="true"],button[aria-invalid="true"],[data-invalid="true"] button',
      ) ?? alert.current;
    target?.scrollIntoView?.({ block: 'nearest' });
    target?.focus();
  }, [props.error, props.open]);
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent side="right" keepMounted>
        <DialogHeader>
          <DialogTitle>配置查询</DialogTitle>
          <DialogDescription>
            关闭仅隐藏并保留草稿。运行被接纳后关闭，在结果区查看进度。
          </DialogDescription>
        </DialogHeader>
        <div
          ref={body}
          aria-label="分析配置面板"
          className="fve:min-h-0 fve:flex-1 fve:overflow-y-auto"
        >
          <OverlayScope visible={props.open}>
            <AnalysisQueryEditor {...props} visible={props.open} />
          </OverlayScope>
        </div>
        {props.error && (
          <p
            role="alert"
            ref={alert}
            tabIndex={-1}
            className="fve:text-sm fve:text-destructive"
          >
            {props.error.message}
          </p>
        )}
        <DialogFooter className="fve:rounded-none">
          <Button variant="outline" onClick={() => props.onOpenChange(false)}>
            查看结果
          </Button>
          <Button onClick={props.onRun}>运行并查看</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
