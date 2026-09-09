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
import {
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { cloneSnapshot } from '../lib/types.js';
import {
  FILTER_OPERATORS,
  clearFilterValues,
  isSimpleFilter,
  newFilterNode,
  createFilterConfiguration,
} from './filterCore.js';
import type {
  FilterComponentConfig,
  FilterConfiguration,
} from './filterModel.js';
import type {
  FilterPanelProps,
  FilterPanelToolbarProps,
} from './filterReactTypes.js';
import { locateFilterNodes, replaceFilterNode } from './filterTree.js';
import {
  appendNode,
  transitionFilterOperator,
} from './filterDraftTransitions.js';
import { message, ownValue, without } from './filterPanelUtils.js';
import { useFilterPanelEditors } from './useFilterPanelEditors.js';
import { useFilterPanelQuery } from './useFilterPanelQuery.js';

export function useFilterPanelState(props: FilterPanelProps) {
  const { fields, disabled = false } = props;
  const [localConfiguration, setLocalConfiguration] = useState(() =>
    cloneSnapshot<FilterConfiguration>(
      props.defaultValue ??
        createFilterConfiguration(newFilterNode(FilterOperator.MATCH_ALL)),
    ),
  );
  const controlled = useMemo(
    () =>
      props.value ? cloneSnapshot<FilterConfiguration>(props.value) : undefined,
    [props.value],
  );
  const configuration = controlled ?? localConfiguration;
  const draft = configuration.root;
  const configurationRef = useRef(configuration);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const [localBaseline, setBaseline] = useState(configuration);
  const baseline = props.appliedValue ?? localBaseline;
  const panelId = useId();
  const simple = isSimpleFilter(draft);
  const mode = configuration.mode;
  const {
    locations,
    compiled,
    applied,
    resolutions,
    clearable,
    issues,
    valid,
    pending,
    epoch,
    editorEpochs,
    setEditorValidity,
    setEditorOutputErrors,
    setEpoch,
    setEditorEpochs,
  } = useFilterPanelEditors(props, configuration, baseline, mode);
  const latest = useRef({ props, simple, issues });
  const emitted = useRef<FilterConfiguration | undefined>(undefined);
  const [observed, setObserved] = useState({
    value: props.value,
    generation: { id: 0 },
  });
  const generation = observed.generation;
  // Render-local state can be discarded by Suspense without invalidating committed callbacks.
  if (!sameJsonState(observed.value, props.value)) {
    const replacement = !sameJsonState(emitted.current, props.value);
    setObserved({
      value: props.value,
      generation: replacement ? { id: generation.id + 1 } : generation,
    });
    if (replacement) {
      setEditorValidity({});
      setEditorOutputErrors({});
    }
  }
  const session = useRef(generation);
  useLayoutEffect(() => {
    configurationRef.current = configuration;
    latest.current = { props, simple, issues };
    session.current = generation;
  }, [configuration, props, simple, issues, generation]);
  // Query-equivalent edits (unset controls and display props) belong to the same applied snapshot.
  useEffect(() => {
    if (
      !props.appliedValue &&
      !pending &&
      !sameJsonState(localBaseline, configuration)
    )
      setBaseline(cloneSnapshot<FilterConfiguration>(configuration));
  }, [props.appliedValue, pending, localBaseline, configuration]);
  const { apply, applyError, setApplyError, submitting } = useFilterPanelQuery(
    props,
    configuration,
    applied,
    compiled,
    valid,
    generation,
    setBaseline,
  );
  function changeConfiguration(next: FilterConfiguration) {
    if (
      latest.current.props.disabled ||
      !mounted.current ||
      sameJsonState(configurationRef.current, next)
    )
      return;
    configurationRef.current = next;
    emitted.current = next;
    if (!latest.current.props.value) setLocalConfiguration(next);
    latest.current.props.onChange?.(next);
    setApplyError(undefined);
    const ids = new Set(
      locateFilterNodes(next.root, fields).map(({ node }) => node.id),
    );
    setEditorValidity(previous =>
      Object.fromEntries(
        Object.entries(previous).filter(([id]) => ids.has(id)),
      ),
    );
    setEditorOutputErrors(previous =>
      Object.fromEntries(
        Object.entries(previous).filter(([id]) => ids.has(id)),
      ),
    );
  }
  function change(root: FilterComponentConfig) {
    changeConfiguration({ ...configurationRef.current, root });
  }
  function update(
    id: string,
    next?: FilterComponentConfig,
    preserveValidity = false,
  ) {
    change(
      replaceFilterNode(configurationRef.current.root, id, next) ??
        newFilterNode(FilterOperator.MATCH_ALL),
    );
    if (!preserveValidity) setEditorValidity(previous => without(previous, id));
    setEditorOutputErrors(previous => without(previous, id));
  }
  function clearNode(node: FilterComponentConfig) {
    try {
      const scope =
        locations.find(location => location.node.id === node.id)?.fields ??
        fields;
      update(
        node.id,
        clearFilterValues(
          node,
          scope,
          props.extensions?.filters,
          props.timeZone,
        ),
      );
      setEditorEpochs(previous => ({
        ...previous,
        [node.id]: (ownValue(previous, node.id) ?? 0) + 1,
      }));
    } catch (error) {
      setEditorOutputErrors(previous => ({
        ...previous,
        [node.id]: message(error),
      }));
    }
  }
  function changeOperator(node: FilterComponentConfig, op: FilterOperator) {
    const next = transitionFilterOperator(node, op);
    const before = FILTER_OPERATORS[node.operator]?.input,
      after = FILTER_OPERATORS[op]?.input;
    change(replaceFilterNode(configurationRef.current.root, node.id, next)!);
    if (
      before !== after &&
      after !== 'none' &&
      [
        'value',
        'values',
        'lowerBound',
        'upperBound',
        'time',
        'days',
        'query',
      ].some(key => node.props[key] !== undefined)
    ) {
      setEditorOutputErrors(previous =>
        ownValue(previous, node.id) !== undefined
          ? previous
          : {
              ...previous,
              [node.id]: '操作已改变，请设置新值，或删除此筛选器。',
            },
      );
    }
  }
  function canAppend(target: FilterComponentConfig) {
    const allowedOperators = latest.current.props.allowedOperators;
    return (
      target.operator === FilterOperator.MATCH_ALL ||
      !!target.operands ||
      !allowedOperators ||
      allowedOperators.includes(FilterOperator.AND)
    );
  }
  function append(target: FilterComponentConfig, child: FilterComponentConfig) {
    const current = locateFilterNodes(
      configurationRef.current.root,
      fields,
    ).find(item => item.node.id === target.id)?.node;
    if (!current || !canAppend(current)) return;
    const next = appendNode(current, child);
    if (mode === 'advanced' || isSimpleFilter(next)) update(current.id, next);
  }
  function currentEditorNode(node: FilterComponentConfig) {
    if (!mounted.current || session.current.id > generation.id)
      return undefined;
    // Child layout effects run before the panel's layout effect. Their callbacks
    // already belong to the committed replacement and must use its current tree.
    if (session.current !== generation) {
      configurationRef.current = configuration;
      latest.current = { props, simple, issues };
      session.current = generation;
    }
    const current = locateFilterNodes(
      configurationRef.current.root,
      fields,
    ).find(location => location.node.id === node.id)?.node;
    return session.current === generation &&
      current?.operator === node.operator &&
      current.field === node.field
      ? current
      : undefined;
  }
  const toolbar: FilterPanelToolbarProps = {
    panelId,
    mode,
    pending,
    disabled,
    options: [
      {
        value: 'simple',
        label: '简单',
        disabled: !simple || (mode === 'advanced' && issues.length > 0),
      },
      { value: 'advanced', label: '高级' },
    ],
    onModeChange(next) {
      const current = latest.current;
      if (
        current.props.disabled ||
        (next === 'simple' && (!current.simple || current.issues.length > 0))
      )
        return;
      changeConfiguration({ ...configurationRef.current, mode: next });
    },
  };
  function removeField(targetId: string, field: string) {
    const current = locateFilterNodes(
      configurationRef.current.root,
      fields,
    ).find(item => item.node.id === targetId)?.node;
    if (!current) return;
    if (current.operands) {
      const operands = current.operands.filter(node => node.field !== field);
      if (operands.length === current.operands.length) return;
      update(
        current.id,
        !operands.length &&
          current.id === configurationRef.current.root.id &&
          current.operator === FilterOperator.AND
          ? newFilterNode(FilterOperator.MATCH_ALL)
          : { ...current, operands },
      );
    } else if (current.field === field) update(current.id);
  }
  function undo() {
    changeConfiguration(cloneSnapshot<FilterConfiguration>(baseline));
    setEditorValidity({});
    setEditorOutputErrors({});
    setEpoch(count => count + 1);
  }
  function clear() {
    if (disabled || !clearable) return;
    try {
      change(
        clearFilterValues(
          configurationRef.current.root,
          fields,
          props.extensions?.filters,
          props.timeZone,
        ),
      );
    } catch (error) {
      setApplyError(message(error));
      return;
    }
    setEditorValidity({});
    setEditorOutputErrors({});
    setEpoch(count => count + 1);
  }
  return {
    props,
    disabled,
    draft,
    mode,
    panelId,
    locations,
    simple,
    resolutions,
    issues,
    pending,
    applyError,
    clearReason: clearable
      ? undefined
      : '当前包含不支持清空值的筛选器，请修改或删除对应条件。',
    epoch,
    editorEpochs,
    session: generation,
    toolbar,
    submitting,
    applyDisabled:
      disabled || issues.length > 0 || !compiled.expression || submitting,
    update,
    clearNode,
    changeOperator,
    append,
    canAppend,
    removeField,
    currentEditorNode,
    setEditorValidity,
    setEditorOutputErrors,
    apply,
    undo,
    clear,
  };
}
export type FilterPanelState = ReturnType<typeof useFilterPanelState>;
