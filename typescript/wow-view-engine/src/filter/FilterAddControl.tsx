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
import { ChevronDownIcon } from 'lucide-react';
import {
  FILTER_OPERATORS,
  getFieldOperators,
  getNamedFilterOperators,
} from './filterOperators.js';
import { newFilterNode } from './filterNodes.js';
import { resolveFilterComponent } from './filterConfiguration.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from './filterModel.js';
import type { FilterPanelState } from './useFilterPanelState.js';
import { logicalOperators, groupLabels } from './filterPanelUtils.js';
import { FilterFieldPicker, type FieldChoice } from './FilterFieldPicker.js';
import { Button } from '../components/ui/button.js';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '../components/ui/dropdown-menu.js';

export function FilterAddControl({
  target,
  scopeFields,
  scope,
  label,
  panel,
}: {
  target: FilterComponentConfig;
  scopeFields: readonly FilterFieldDefinition[];
  scope: string;
  label: string;
  panel: FilterPanelState;
}) {
  const { props, mode, disabled, append, canAppend, removeField } = panel;
  const canAdd = canAppend(target);
  const bindings = target.operands ?? [target];
  const options: FieldChoice[] = scopeFields.flatMap(field => {
    const operators = getFieldOperators(field).filter(
      op =>
        (!props.allowedOperators || props.allowedOperators.includes(op)) &&
        (getNamedFilterOperators(
          resolveFilterComponent(op, field, props.editors).name,
        )?.includes(op) ??
          true),
    );
    if (
      !operators.length ||
      (!canAdd && !bindings.some(node => node.field === field.field)) ||
      (mode === 'simple' &&
        field.type === 'array' &&
        operators.every(op => FILTER_OPERATORS[op].category === 'element'))
    )
      return [];
    return [
      {
        value: `field:${field.field}`,
        label: field.label,
        group: field.group ?? '',
        count: bindings.filter(node => node.field === field.field).length,
        repeatable: mode === 'advanced' && canAdd,
      },
    ];
  });
  if (mode === 'advanced' && canAdd) {
    for (const op of Object.values(FilterOperator))
      if (
        FILTER_OPERATORS[op].category === 'root' &&
        (scope === 'root' ||
          op === FilterOperator.MATCH_ALL ||
          op === FilterOperator.MATCH_NONE) &&
        (!props.allowedOperators || props.allowedOperators.includes(op))
      )
        options.push({
          value: `op:${op}`,
          label: FILTER_OPERATORS[op].label,
          group: '其他条件',
        });
  }
  return (
    <DropdownMenu>
      <FilterFieldPicker
        label={label}
        options={options}
        disabled={disabled || options.length === 0}
        onAdd={choice => {
          const option = options.find(option => option.value === choice);
          if (disabled || !choice || !option) return;
          if (choice.startsWith('op:'))
            append(
              target,
              newFilterNode(
                choice.slice(3) as FilterOperator,
                undefined,
                resolveFilterComponent(
                  choice.slice(3) as FilterOperator,
                  undefined,
                  props.editors,
                ),
              ),
            );
          else {
            const field = scopeFields.find(
              field => field.field === choice.slice(6),
            )!;
            const op = getFieldOperators(field).find(
              op =>
                (!props.allowedOperators ||
                  props.allowedOperators.includes(op)) &&
                (mode === 'advanced' ||
                  FILTER_OPERATORS[op].category !== 'element') &&
                (getNamedFilterOperators(
                  resolveFilterComponent(op, field, props.editors).name,
                )?.includes(op) ??
                  true),
            )!;
            if (op)
              append(
                target,
                newFilterNode(
                  op,
                  field.field,
                  resolveFilterComponent(op, field, props.editors),
                ),
              );
          }
        }}
        onRemove={choice => {
          if (disabled || !choice.startsWith('field:')) return;
          const field = choice.slice(6);
          removeField(target.id, field);
        }}
      >
        {mode === 'advanced' && (
          <DropdownMenuTrigger
            aria-label={
              label === '添加筛选' ? '添加逻辑分组' : `${label}：添加逻辑分组`
            }
            title="添加逻辑分组"
            disabled={
              disabled ||
              !canAdd ||
              logicalOperators.every(
                op =>
                  props.allowedOperators &&
                  !props.allowedOperators.includes(op),
              )
            }
            render={<Button type="button" variant="outline" size="icon-sm" />}
          >
            <ChevronDownIcon aria-hidden="true" />
          </DropdownMenuTrigger>
        )}
      </FilterFieldPicker>
      {mode === 'advanced' && (
        <DropdownMenuContent align="start" className="fve:min-w-56">
          {logicalOperators.map(op => (
            <DropdownMenuItem
              key={op}
              disabled={
                disabled ||
                (!!props.allowedOperators &&
                  !props.allowedOperators.includes(op))
              }
              onClick={() => {
                if (
                  !disabled &&
                  (!props.allowedOperators ||
                    props.allowedOperators.includes(op))
                )
                  append(target, newFilterNode(op));
              }}
            >
              <span className="fve:w-8 fve:font-medium">{op}</span>
              <span className="fve:text-muted-foreground">
                {groupLabels[op]}
              </span>
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      )}
    </DropdownMenu>
  );
}
