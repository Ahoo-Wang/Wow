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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { XIcon } from 'lucide-react';
import { FILTER_OPERATORS, getFieldOperators } from './filterOperators.js';
import { transitionFilterOperator } from './filterDraftTransitions.js';
import { resolveFilterEditor } from './resolveFilterEditor.js';
import type { FilterComponentConfig } from './filterModel.js';
import type { FilterPanelState } from './useFilterPanelState.js';
import {
  logicalOperators,
  groupLabels,
  filterLayout,
} from './filterPanelUtils.js';
import { FilterSelect } from './FilterSelect.js';
import { FilterAddControl } from './FilterAddControl.js';
import { FilterLeafEditor } from './FilterLeafEditor.js';
import { FieldFilter } from './FieldFilter.js';
import { Button } from '../components/ui/button.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
} from '../components/ui/input-group.js';

export function FilterNode({
  node,
  showAddControl = true,
  panel,
}: {
  node: FilterComponentConfig;
  showAddControl?: boolean;
  panel: FilterPanelState;
}) {
  const {
    props,
    locations,
    panelId,
    issues,
    epoch,
    disabled,
    update,
    mode,
    changeOperator,
    resolutions,
  } = panel;
  const location = locations.find(location => location.node.id === node.id)!;
  const descriptor = FILTER_OPERATORS[node.operator];
  const field = location.fields.find(field => field.field === node.field);
  const label =
    field?.label ?? node.field ?? descriptor?.label ?? String(node.operator);
  const nodeIssues = issues.filter(issue => issue.id === node.id);
  const errorId = `${panelId}-${node.id}-error`;
  if (node.operands || node.operator === FilterOperator.ELEMENT_MATCH) {
    const element = node.operator === FilterOperator.ELEMENT_MATCH;
    return (
      <div
        key={`${epoch}:${node.id}`}
        data-filter-id={node.id}
        className="fve:col-span-full fve:min-w-0 fve:w-full fve:rounded-lg fve:border fve:border-border fve:p-3"
        role="group"
        aria-label={
          element
            ? `${label}元素条件`
            : `条件组合 ${locations.indexOf(location) + 1}`
        }
        aria-describedby={nodeIssues.length ? errorId : undefined}
      >
        <div className="fve:mb-2 fve:flex fve:flex-wrap fve:items-center fve:gap-2">
          {element ? (
            <strong>{label}：同一元素满足</strong>
          ) : (
            <FilterSelect
              label="组合方式"
              value={node.operator}
              options={logicalOperators.map(op => ({
                value: op,
                label: groupLabels[op],
                disabled:
                  !!props.allowedOperators &&
                  !props.allowedOperators.includes(op),
              }))}
              disabled={disabled}
              onValueChange={op => update(node.id, { ...node, operator: op })}
            />
          )}
          <Button
            variant="ghost"
            size="icon-xs"
            aria-label={`删除${label}条件`}
            disabled={disabled}
            onClick={() => update(node.id)}
          >
            <XIcon aria-hidden="true" />
          </Button>
        </div>
        <div className={filterLayout}>
          {element
            ? node.predicate && (
                <FilterNode
                  key={`${epoch}:${node.predicate.id}`}
                  node={node.predicate}
                  showAddControl={false}
                  panel={panel}
                />
              )
            : node.operands?.map(child => (
                <FilterNode
                  key={`${epoch}:${child.id}`}
                  node={child}
                  panel={panel}
                />
              ))}
        </div>
        {element && node.predicate && (
          <div className="fve:mt-2">
            <FilterAddControl
              target={node.predicate}
              scopeFields={field?.fields ?? []}
              scope={
                locations.find(item => item.node.id === node.predicate!.id)!
                  .scope
              }
              label={`${label}元素内添加筛选`}
              panel={panel}
            />
          </div>
        )}
        {!element && showAddControl && (
          <div className="fve:mt-2">
            <FilterAddControl
              target={node}
              scopeFields={location.fields}
              scope={location.scope}
              label={`添加到组合 ${locations.indexOf(location) + 1}`}
              panel={panel}
            />
          </div>
        )}
        {nodeIssues.length > 0 && (
          <div role="alert" id={errorId}>
            {nodeIssues.map(issue => issue.message).join('；')}
          </div>
        )}
      </div>
    );
  }
  const operators = (
    field
      ? getFieldOperators(field)
      : Object.values(FilterOperator).filter(
          op =>
            FILTER_OPERATORS[op].category === 'root' &&
            (location.scope === 'root' ||
              op === FilterOperator.MATCH_ALL ||
              op === FilterOperator.MATCH_NONE),
        )
  ).filter(
    op =>
      (!props.allowedOperators || props.allowedOperators.includes(op)) &&
      (mode === 'advanced' || FILTER_OPERATORS[op].category === 'field') &&
      !resolveFilterEditor(
        {
          ...location,
          node:
            op === node.operator ? node : transitionFilterOperator(node, op),
        },
        props,
        mode,
      ).error,
  );
  const options = [...new Set([...operators, node.operator])].map(op => ({
    value: op,
    label: FILTER_OPERATORS[op]?.label ?? String(op),
    disabled: !operators.includes(op),
  }));
  const editor = (
    <FilterLeafEditor
      location={location}
      operators={options}
      errors={nodeIssues.map(issue => issue.message)}
      errorId={errorId}
      panel={panel}
    />
  );
  const complete = resolutions.get(node.id)?.registration?.render === 'filter';
  return (
    <div
      key={`${epoch}:${node.id}`}
      data-filter-id={node.id}
      data-slot="filter-cell"
      className="fve:min-w-0 fve:max-w-full"
      aria-describedby={nodeIssues.length ? errorId : undefined}
    >
      {complete ? (
        editor
      ) : field || node.field ? (
        <FieldFilter
          field={{ field: node.field!, label }}
          operator={node.operator}
          operators={options}
          onOperatorChange={op => changeOperator(node, op)}
          onRemove={() => update(node.id)}
          disabled={disabled}
        >
          {editor}
        </FieldFilter>
      ) : (
        <InputGroup
          aria-label={label}
          className="fve:h-auto fve:min-h-8 fve:w-full fve:max-w-full fve:flex-wrap"
        >
          <FilterSelect
            label="特殊条件类型"
            value={node.operator}
            options={options}
            onValueChange={op => changeOperator(node, op)}
            inline
            disabled={disabled}
          />
          {editor}
          <InputGroupAddon align="inline-end" className="fve:ml-auto">
            <InputGroupButton
              aria-label={`删除${label}条件`}
              size="icon-xs"
              disabled={disabled}
              onClick={() => update(node.id)}
            >
              <XIcon aria-hidden="true" />
            </InputGroupButton>
          </InputGroupAddon>
        </InputGroup>
      )}
      {nodeIssues.length > 0 && (
        <div
          role="alert"
          id={errorId}
          className="fve:mt-1 fve:text-sm fve:text-destructive"
        >
          {nodeIssues.map(issue => issue.message).join('；')}
        </div>
      )}
    </div>
  );
}
