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

import { useEffect, useRef, useState } from 'react';
import type { DeepReadonly } from '../lib/types.js';
import type { FilterConfiguration, FilterMode } from './filterModel.js';
import type { FilterPanelProps } from './filterReactTypes.js';
import { compileFilterConfiguration, FILTER_OPERATORS } from './filterCore.js';
import { locateFilterNodes, sameFilterQuery } from './filterTree.js';
import { resolveFilterEditor } from './resolveFilterEditor.js';
import { ownValue } from './filterPanelUtils.js';
import { filterClearCompiler } from './filterConfigurationClear.js';

/** Resolve extension support and combine local input validity with protocol validation. */
export function useFilterPanelEditors(
  props: FilterPanelProps,
  configuration: FilterConfiguration,
  baseline: DeepReadonly<FilterConfiguration>,
  mode: FilterMode,
) {
  const { fields, onPendingChange, onValidityChange } = props;
  const [editorValidity, setEditorValidity] = useState<Record<string, string>>(
    {},
  );
  const [editorOutputErrors, setEditorOutputErrors] = useState<
    Record<string, string>
  >({});
  const [epoch, setEpoch] = useState(0);
  const [editorEpochs, setEditorEpochs] = useState<Record<string, number>>({});
  const locations = locateFilterNodes(configuration.root, fields);
  const compiled = compileFilterConfiguration(
    configuration,
    fields,
    props.allowedOperators,
    props.extensions?.filters,
    props.timeZone,
  );
  const resolutions = new Map(
    locations
      .filter(
        ({ node }) =>
          !['logical', 'element'].includes(
            FILTER_OPERATORS[node.operator]?.category,
          ),
      )
      .map(location => [
        location.node.id,
        resolveFilterEditor(location, props, mode),
      ]),
  );
  const clearable = locations.every(({ node }) => {
    if (
      ['logical', 'element'].includes(FILTER_OPERATORS[node.operator]?.category)
    )
      return true;
    try {
      const reference = node.component;
      return !!filterClearCompiler(reference.name, props.extensions?.filters)
        .clear;
    } catch {
      return false;
    }
  });
  const issues = locations.flatMap(({ node }) =>
    [
      ...compiled.errors
        .filter(error => error.id === node.id)
        .map(error => error.message),
      ownValue(editorValidity, node.id),
      ownValue(editorOutputErrors, node.id),
      resolutions.get(node.id)?.error,
    ]
      .filter((item): item is string => item !== undefined)
      .map(text => ({
        id: node.id,
        message: text || '输入尚未完成或格式无效。',
      })),
  );
  const valid = issues.length === 0;
  const applied = compileFilterConfiguration(
    baseline,
    fields,
    props.allowedOperators,
    props.extensions?.filters,
    props.timeZone,
  );
  const pending =
    !valid ||
    !compiled.expression ||
    !sameFilterQuery(compiled.expression, applied.expression);
  const previousValidity = useRef<boolean | undefined>(undefined);
  useEffect(() => {
    if (previousValidity.current !== valid) {
      previousValidity.current = valid;
      onValidityChange?.(valid);
    }
  }, [valid, onValidityChange]);
  const previousPending = useRef<boolean | undefined>(undefined);
  useEffect(() => {
    if (previousPending.current !== pending) {
      previousPending.current = pending;
      onPendingChange?.(pending);
    }
  }, [pending, onPendingChange]);

  return {
    locations,
    compiled,
    applied: applied.expression,
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
  };
}
