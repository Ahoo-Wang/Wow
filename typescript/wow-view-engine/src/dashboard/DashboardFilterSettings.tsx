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

import { dashboardEditorKey } from './dashboardEditorKey.js';
import {
  Component,
  type ComponentType,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react';
import type { DashboardTransformEditorProps } from './dashboardReactTypes.js';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { ErrorBoundary } from 'react-error-boundary';
import { RenderCommit } from '../lib/RenderCommit.js';
import { Button } from '../components/ui/button.js';
import { Checkbox } from '../components/ui/checkbox.js';
import { Input } from '../components/ui/input.js';
import { FilterPanel } from '../filter/FilterPanel.js';
import {
  createFilterConfiguration,
  newFilterNode,
  compileFilterConfiguration,
} from '../filter/filterCore.js';
import {
  parseFilterOutput,
  type ProtocolNode,
} from '../filter/filterProtocol.js';
import { FILTER_OPERATORS } from '../filter/filterOperators.js';
import type { FilterFieldDefinition } from '../filter/filterModel.js';
import type { ViewDefinition } from '../contracts/viewModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { message } from '../lib/snapshot.js';
import type { ViewExtensions } from '../view/viewReactTypes.js';
import type {
  DashboardRuntime,
  DashboardSnapshot,
} from './DashboardRuntime.js';
import type { DashboardBinding, DashboardFilter } from './dashboardModel.js';

export function DashboardFilterSettings({
  runtime,
  label,
  snapshot,
  extensions,
  filterContext,
  configuring,
  repair,
}: {
  runtime: DashboardRuntime;
  label?: string;
  snapshot: DashboardSnapshot;
  extensions?: ViewExtensions;
  filterContext?: unknown;
  configuring: boolean;
  repair?: { panelId: string; filterId?: string; version: number };
}) {
  const [error, setError] = useState<string | null>(null);
  function run(action: () => void) {
    try {
      action();
      setError(null);
    } catch (reason) {
      setError(message(reason));
    }
  }
  const definition = runtime.definition;
  return (
    <section
      aria-label={label ?? '全局筛选'}
      className="fve:flex fve:min-w-0 fve:flex-col fve:gap-3"
    >
      {snapshot.config.filters.map((item, index) => (
        <section
          key={`${runtime.identity}:${item.id}:${snapshot.session.editorEpoch}`}
          aria-label={`${label ?? '全局筛选'}${index + 1}`}
          className="fve:min-w-0 fve:rounded-lg fve:border fve:p-3"
        >
          <div className="fve:mb-2 fve:flex fve:items-center fve:justify-between fve:gap-2">
            <h3 className="fve:text-sm fve:font-medium">
              全局筛选 {index + 1}
            </h3>
            {snapshot.editable && configuring && (
              <Button
                size="sm"
                variant="ghost"
                onClick={() =>
                  run(() =>
                    runtime.edit(config => ({
                      ...config,
                      filters: config.filters.filter(
                        filter => filter.id !== item.id,
                      ),
                    })),
                  )
                }
              >
                移除全局筛选 {index + 1}
              </Button>
            )}
          </div>
          <FilterPanel
            ariaLabel={label ? `${label}${index + 1}筛选器` : undefined}
            value={item.filters}
            appliedValue={
              snapshot.applied.filters.find(filter => filter.id === item.id)
                ?.filters
            }
            fields={definition.fields}
            timeZone={definition.timeZone}
            allowedOperators={definition.allowedOperators}
            extensions={extensions}
            context={filterContext}
            showQueryAction={false}
            onApply={() => {}}
            onChange={filters => run(() => runtime.setFilter(item.id, filters))}
            onValidityChange={valid =>
              runtime.setEditorValidity(dashboardEditorKey(item.id), valid)
            }
          />
          <div
            hidden={!configuring || !snapshot.editable}
            className="fve:mt-3 fve:flex fve:flex-col fve:gap-3"
          >
            <p className="fve:text-sm fve:text-muted-foreground">
              为每个数据面板明确选择字段映射、宿主转换或不参与。关闭设置保留草稿；点击查询才应用。
            </p>
            {[...snapshot.config.panels]
              .sort(
                (a, b) => a.layout.y - b.layout.y || a.layout.x - b.layout.x,
              )
              .map(
                (panel, panelIndex) =>
                  panel.kind === 'view' && (
                    <BindingRow
                      key={JSON.stringify([
                        panel.id,
                        panel.instanceId,
                        snapshot.session.baseline.config.filters
                          .find(filter => filter.id === item.id)
                          ?.bindings.find(
                            binding => binding.panelId === panel.id,
                          ),
                        snapshot.session.baseline.config.filters
                          .find(filter => filter.id === item.id)
                          ?.excludedPanelIds.includes(panel.id),
                      ])}
                      runtime={runtime}
                      item={item}
                      panelId={panel.id}
                      title={`${snapshot.panels[panel.id]?.instance?.title ?? panel.instanceId}（面板 ${panelIndex + 1}）`}
                      target={snapshot.panels[panel.id]?.definition}
                      extensions={extensions}
                      focus={
                        repair?.panelId === panel.id &&
                        (repair.filterId
                          ? repair.filterId === item.id
                          : index === 0)
                          ? repair.version
                          : undefined
                      }
                      run={run}
                    />
                  ),
              )}
          </div>
        </section>
      ))}
      {snapshot.editable && configuring && (
        <Button
          variant="outline"
          className="fve:self-start"
          onClick={() =>
            run(() =>
              runtime.edit(config => ({
                ...config,
                filters: [
                  ...config.filters,
                  {
                    id: crypto.randomUUID(),
                    filters: createFilterConfiguration(
                      newFilterNode(FilterOperator.MATCH_ALL),
                    ),
                    bindings: [],
                    excludedPanelIds: [],
                  },
                ],
              })),
            )
          }
        >
          添加全局筛选
        </Button>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}

function BindingRow({
  runtime,
  item,
  panelId,
  title,
  target,
  extensions,
  focus,
  run,
}: {
  runtime: DashboardRuntime;
  item: DeepReadonly<DashboardFilter>;
  panelId: string;
  title: string;
  target?: DeepReadonly<ViewDefinition>;
  extensions?: ViewExtensions;
  focus?: number;
  run(action: () => void): void;
}) {
  const id = useId();
  const rowRef = useRef<HTMLFieldSetElement>(null);
  useEffect(() => {
    if (focus !== undefined) rowRef.current?.focus();
  }, [focus]);
  const binding = item.bindings.find(value => value.panelId === panelId);
  const excluded = item.excludedPanelIds.includes(panelId);
  const [mode, setMode] = useState(
    binding?.kind ?? (excluded ? 'excluded' : ''),
  );
  const [mapping, setMapping] = useState<Record<string, string>>(
    binding?.kind === 'fields' ? { ...binding.fields } : {},
  );
  const [confirmed, setConfirmed] = useState(binding?.kind === 'fields');
  const [search, setSearch] = useState('');
  const source = runtime.definition;
  const compiled = compileFilterConfiguration(
    item.filters,
    source.fields,
    source.allowedOperators,
    runtime.filterCompilers,
    source.timeZone,
  );
  const paths: {
    path: string;
    field?: FilterFieldDefinition;
    operator: FilterOperator;
    parent?: string;
  }[] = [];
  let implicit = false;
  function visit(
    node: ProtocolNode,
    fields: readonly FilterFieldDefinition[],
    prefix = '',
    parent?: string,
  ) {
    function add(path: string) {
      paths.push({
        path: prefix + path,
        field: fields.find(field => field.field === path),
        operator: node.op,
        parent,
      });
    }
    if (node.field) add(node.field);
    node.fields?.forEach(add);
    if (
      FILTER_OPERATORS[node.op].category === 'root' &&
      ![FilterOperator.MATCH_ALL, FilterOperator.MATCH_NONE].includes(
        node.op,
      ) &&
      !(node.op === FilterOperator.SEARCH && node.fields?.length)
    )
      implicit = true;
    node.operands?.forEach(child => visit(child, fields, prefix, parent));
    if (node.predicate)
      visit(
        node.predicate,
        fields.find(field => field.field === node.field)?.fields ?? [],
        `${prefix}${node.field}.`,
        `${prefix}${node.field}`,
      );
  }
  if (compiled.expression)
    visit(parseFilterOutput(compiled.expression), source.fields);
  const referenced = [
    ...new Map(paths.map(path => [path.path, path])).values(),
  ];
  function flatten(
    fields: readonly FilterFieldDefinition[],
    prefix = '',
    parent?: string,
  ): { path: string; field: FilterFieldDefinition; parent?: string }[] {
    return fields.flatMap(field => [
      { path: prefix + field.field, field, parent },
      ...flatten(
        field.fields ?? [],
        prefix + field.field + '.',
        prefix + field.field,
      ),
    ]);
  }
  const targets = flatten(target?.fields ?? []);
  function update(next?: DashboardBinding, isExcluded = false) {
    runtime.edit(config => ({
      ...config,
      filters: config.filters.map(filter =>
        filter.id === item.id
          ? {
              ...filter,
              bindings: [
                ...filter.bindings.filter(value => value.panelId !== panelId),
                ...(next ? [next] : []),
              ],
              excludedPanelIds: [
                ...filter.excludedPanelIds.filter(value => value !== panelId),
                ...(isExcluded ? [panelId] : []),
              ],
            }
          : filter,
      ),
    }));
  }
  function fields(next: Record<string, string>, compatible: boolean) {
    setMapping(next);
    setConfirmed(compatible);
    update(
      compatible &&
        !implicit &&
        compiled.expression &&
        referenced.every(path => next[path.path])
        ? { panelId, kind: 'fields', fields: next, semanticCompatibility: true }
        : undefined,
    );
  }
  const transforms = Object.entries(
    extensions?.dashboard?.transforms ?? {},
  ).filter(([, value]) => {
    try {
      return !value.applicable || (target && value.applicable(source, target));
    } catch {
      return false;
    }
  });
  const registration =
    binding?.kind === 'transform'
      ? transforms.find(([name]) => name === binding.name)?.[1]
      : undefined;
  const Editor = registration?.Editor;
  return (
    <fieldset
      tabIndex={-1}
      ref={rowRef}
      data-binding-panel={panelId}
      className="fve:min-w-0 fve:rounded-md fve:border fve:p-3"
    >
      <legend className="fve:max-w-full fve:break-words fve:px-1 fve:text-sm fve:font-medium">
        {title} · {target?.title ?? '来源不可用'}
      </legend>
      <p className="fve:mb-2 fve:break-words fve:text-xs fve:text-muted-foreground">
        {excluded
          ? '明确不参与此筛选'
          : binding?.kind === 'fields'
            ? Object.entries(binding.fields)
                .map(([from, to]) => `${from} → ${to}`)
                .join('；') || '常量条件，无字段映射'
            : binding?.kind === 'transform'
              ? `宿主转换：${binding.name}`
              : '尚未决定：查询和保存前必须完成配置'}
      </p>
      <label className="fve:flex fve:flex-col fve:gap-2 fve:text-sm">
        绑定方式
        <select
          aria-label={`${title}绑定方式`}
          className="fve:h-9 fve:max-w-full fve:rounded-md fve:border fve:bg-background fve:px-2"
          value={mode}
          onChange={event =>
            run(() => {
              const value = event.target.value;
              setMode(value);
              setConfirmed(false);
              update(undefined, value === 'excluded');
            })
          }
        >
          <option value="">请选择绑定方式</option>
          <option value="fields">字段映射</option>
          <option value="transform">宿主转换</option>
          <option value="excluded">不参与</option>
        </select>
      </label>
      {mode === 'fields' && (
        <div className="fve:mt-3 fve:flex fve:flex-col fve:gap-3">
          {implicit && <p role="alert">此条件包含隐式根语义，需要宿主转换。</p>}
          {!target && <p>引用加载后才能选择兼容字段；请重新加载引用。</p>}
          <label className="fve:flex fve:flex-col fve:gap-1 fve:text-sm">
            搜索目标字段
            <Input
              value={search}
              onChange={event => setSearch(event.target.value)}
            />
          </label>
          {referenced.map(path => (
            <label
              key={path.path}
              className="fve:flex fve:min-w-0 fve:flex-col fve:gap-1 fve:break-words fve:text-sm"
            >
              {path.path} · {path.field?.type ?? '未知类型'}
              {path.parent ? ` · 元素作用域 ${path.parent}` : ' · 根作用域'}
              <select
                aria-label={`${title}映射${path.path}`}
                className="fve:h-9 fve:w-full fve:min-w-0 fve:rounded-md fve:border fve:bg-background fve:px-2"
                value={mapping[path.path] ?? ''}
                onChange={event =>
                  run(() =>
                    fields(
                      { ...mapping, [path.path]: event.target.value },
                      false,
                    ),
                  )
                }
              >
                <option value="">请选择兼容目标字段</option>
                {targets
                  .filter(
                    candidate =>
                      candidate.path === mapping[path.path] ||
                      (path.field?.type &&
                        path.field.type === candidate.field.type &&
                        (path.parent
                          ? !!mapping[path.parent] &&
                            candidate.parent === mapping[path.parent]
                          : !candidate.parent) &&
                        paths
                          .filter(item => item.path === path.path)
                          .every(
                            item =>
                              FILTER_OPERATORS[item.operator].category ===
                                'root' ||
                              !candidate.field.operators ||
                              candidate.field.operators.includes(item.operator),
                          ) &&
                        (!['date', 'datetime'].includes(path.field.type) ||
                          source.timeZone === target?.timeZone) &&
                        `${candidate.path} ${candidate.field.label}`
                          .toLowerCase()
                          .includes(search.toLowerCase())),
                  )
                  .map(candidate => (
                    <option key={candidate.path} value={candidate.path}>
                      {candidate.path} · {candidate.field.label} ·{' '}
                      {candidate.field.type}
                    </option>
                  ))}
              </select>
            </label>
          ))}
          <label
            htmlFor={id}
            className="fve:flex fve:items-center fve:gap-2 fve:text-sm"
          >
            <Checkbox
              id={id}
              checked={confirmed}
              disabled={
                implicit ||
                !compiled.expression ||
                !target ||
                referenced.some(path => !mapping[path.path])
              }
              onCheckedChange={checked => run(() => fields(mapping, checked))}
            />
            确认含义、业务编码和时间语义兼容
          </label>
        </div>
      )}
      {mode === 'transform' && (
        <div className="fve:mt-3 fve:flex fve:flex-col fve:gap-3">
          <label className="fve:flex fve:flex-col fve:gap-1 fve:text-sm">
            宿主转换器
            <select
              aria-label={`${title}宿主转换器`}
              className="fve:h-9 fve:max-w-full fve:rounded-md fve:border fve:bg-background fve:px-2"
              value={binding?.kind === 'transform' ? binding.name : ''}
              onChange={event =>
                run(() => {
                  const name = event.target.value;
                  update(
                    name ? { panelId, kind: 'transform', name } : undefined,
                  );
                  runtime.setEditorValidity(
                    dashboardEditorKey(item.id, panelId),
                    !name ||
                      !transforms.find(([key]) => key === name)?.[1].hasOptions,
                  );
                })
              }
            >
              <option value="">请选择转换器</option>
              {binding?.kind === 'transform' && !registration && (
                <option value={binding.name}>
                  {binding.name}（不支持编辑，保留原配置）
                </option>
              )}
              {transforms.map(([name, value]) => (
                <option
                  key={name}
                  value={name}
                  disabled={value.hasOptions && !value.Editor}
                >
                  {value.label}
                  {value.hasOptions && !value.Editor
                    ? '（缺少参数编辑器）'
                    : ''}
                </option>
              ))}
            </select>
          </label>
          {binding?.kind === 'transform' &&
            (!registration || (registration.hasOptions && !Editor)) && (
              <p role="alert">
                宿主未提供此转换配置的编辑支持。原配置已保留，请联系宿主或明确选择其他绑定。
              </p>
            )}
          {binding?.kind === 'transform' && Editor && (
            <TransformEditorBoundary
              key={binding.name}
              editor={Editor}
              hasOptions={registration?.hasOptions === true}
              value={binding.options ?? {}}
              onChange={options => run(() => update({ ...binding, options }))}
              onValidityChange={valid =>
                runtime.setEditorValidity(
                  dashboardEditorKey(item.id, panelId),
                  valid,
                )
              }
            />
          )}
          {!transforms.length && <p>宿主尚未提供可用转换器。</p>}
        </div>
      )}
    </fieldset>
  );
}

/** Retained host callbacks belong to one committed editor lifetime. */
class TransformEditorSession extends Component<
  DashboardTransformEditorProps & {
    editor: ComponentType<DashboardTransformEditorProps>;
  }
> {
  private active = true;
  state = { editor: this.props.editor, generation: {} };
  private committed = { props: this.props, generation: this.state.generation };
  static getDerivedStateFromProps(
    props: TransformEditorSession['props'],
    state: TransformEditorSession['state'],
  ) {
    return props.editor === state.editor
      ? null
      : { editor: props.editor, generation: {} };
  }
  getSnapshotBeforeUpdate() {
    this.committed = { props: this.props, generation: this.state.generation };
    return null;
  }
  componentDidUpdate() {}
  componentDidMount() {
    this.active = true;
    this.committed = { props: this.props, generation: this.state.generation };
  }
  componentWillUnmount() {
    this.active = false;
  }
  render() {
    const { editor: Editor, value } = this.props;
    const generation = this.state.generation;
    const current = () =>
      this.active && this.committed.generation === generation;
    return (
      <Editor
        value={value}
        onChange={options => {
          if (current()) this.committed.props.onChange(options);
        }}
        onValidityChange={valid => {
          if (current()) this.committed.props.onValidityChange(valid);
        }}
      />
    );
  }
}

function TransformEditorBoundary({
  editor,
  hasOptions,
  value,
  onChange,
  onValidityChange,
}: DashboardTransformEditorProps & {
  editor: ComponentType<DashboardTransformEditorProps>;
  hasOptions: boolean;
}) {
  const validity = useRef({
    editor,
    hasOptions,
    input: undefined as boolean | undefined,
    failed: false,
  });
  function current() {
    if (
      validity.current.editor !== editor ||
      validity.current.hasOptions !== hasOptions
    )
      validity.current = {
        editor,
        hasOptions,
        input: undefined,
        failed: validity.current.failed,
      };
    return validity.current;
  }
  return (
    <ErrorBoundary
      resetKeys={[editor, hasOptions]}
      onError={() => {
        current().failed = true;
        onValidityChange(false);
      }}
      fallbackRender={({ resetErrorBoundary }) => (
        <div role="alert">
          转换器编辑器无法显示，请重试或选择其他绑定。
          <Button variant="outline" onClick={resetErrorBoundary}>
            重试转换器编辑器
          </Button>
        </div>
      )}
    >
      <RenderCommit
        onCommit={() => {
          const state = current();
          if (state.failed) {
            state.failed = false;
            if (state.input !== undefined || !hasOptions)
              onValidityChange(state.input ?? true);
          }
        }}
      >
        <TransformEditorSession
          editor={editor}
          value={value}
          onChange={onChange}
          onValidityChange={valid => {
            const state = current();
            state.input = valid;
            if (!state.failed) onValidityChange(valid);
          }}
        />
      </RenderCommit>
    </ErrorBoundary>
  );
}
