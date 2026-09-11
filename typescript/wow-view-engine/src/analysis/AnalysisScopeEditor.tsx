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
import { useEffect, useState } from 'react';
import { Button } from '../components/ui/button.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import { FilterPanel } from '../filter/FilterPanel.js';
import { createFilterConfiguration } from '../filter/filterConfiguration.js';
import { cloneSnapshot } from '../lib/types.js';
import type { AnalysisEditorProps } from './AnalysisEditor.js';
import type { AnalysisViewConfig } from './analysisModel.js';
export function AnalysisScopeEditor({
  value,
  appliedValue,
  context,
  onChange,
  disabled,
  extensions,
  filterContext,
  onFilterValidityChange,
}: AnalysisEditorProps) {
  const scope = context.capability.scopes?.find(
    item => item.id === value.scope?.id,
  );
  const [validity, setValidity] = useState<Record<string, boolean>>({});
  const valid = (scope?.elements ?? []).every(
    (_, index) =>
      validity[
        `${scope!.id}:${index}:${value.scope?.filters[index]?.root.id ?? ''}`
      ] !== false,
  );
  useEffect(() => {
    onFilterValidityChange?.(valid);
  }, [valid, onFilterValidityChange]);
  const all = () =>
    createFilterConfiguration({
      id: crypto.randomUUID(),
      component: { name: 'builtin' },
      operator: FilterOperator.MATCH_ALL,
      props: {},
    });
  if (!value.scope && !context.capability.scopes?.length) return null;
  return (
    <fieldset
      disabled={disabled}
      className="fve:m-0 fve:min-w-0 fve:space-y-2 fve:border-0 fve:p-0"
    >
      <legend className="fve:sr-only">统计对象</legend>
      <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
        <span
          aria-hidden="true"
          className="fve:w-24 fve:shrink-0 fve:text-sm fve:font-medium"
        >
          统计对象
        </span>
        <FilterSelect
          label="统计对象"
          value={value.scope?.id ?? ''}
          disabled={disabled}
          options={[
            { value: '', label: '根记录' },
            ...(context.capability.scopes ?? []).map(item => ({
              value: item.id,
              label: item.label,
            })),
          ]}
          onValueChange={id => {
            if (disabled || id === (value.scope?.id ?? '')) return;
            const selected = context.capability.scopes?.find(
              item => item.id === id,
            );
            onChange({
              ...cloneSnapshot<AnalysisViewConfig>(value),
              scope: selected
                ? { id, filters: selected.elements.map(all) }
                : undefined,
            });
          }}
        />
      </div>
      {value.scope && !scope && (
        <p role="alert">统计对象已不可用，请重新选择。</p>
      )}
      {scope && (
        <p className="fve:text-xs fve:text-muted-foreground">
          按元素计数；根记录筛选仍然生效。切换对象后，请检查现有字段。
        </p>
      )}
      {scope?.elements.map((element, index) => (
        <div key={`${scope.id}:${index}`} className="fve:space-y-2">
          <p className="fve:text-xs fve:font-medium">
            第 {index + 1} 层 · {element.path} 元素筛选
          </p>
          {value.scope?.filters[index] ? (
            <FilterPanel
              value={value.scope.filters[index]}
              appliedValue={
                appliedValue?.scope?.id === scope.id
                  ? appliedValue.scope.filters[index]
                  : undefined
              }
              fields={element.fields}
              timeZone={context.timeZone}
              allowedOperators={context.allowedOperators}
              disabled={disabled}
              extensions={extensions}
              context={filterContext}
              onValidityChange={valid => {
                const key = `${scope.id}:${index}:${value.scope?.filters[index]?.root.id ?? ''}`;
                setValidity(previous =>
                  previous[key] === valid
                    ? previous
                    : { ...previous, [key]: valid },
                );
              }}
              showQueryAction={false}
              onApply={() => {}}
              onChange={filter => {
                if (disabled) return;
                const next = cloneSnapshot<AnalysisViewConfig>(value);
                next.scope!.filters[index] = filter;
                onChange(next);
              }}
            />
          ) : (
            <Button
              variant="outline"
              disabled={disabled}
              onClick={() => {
                if (disabled) return;
                const next = cloneSnapshot<AnalysisViewConfig>(value);
                next.scope!.filters = scope.elements.map(
                  (_, i) => next.scope!.filters[i] ?? all(),
                );
                onChange(next);
              }}
            >
              修复元素筛选配置
            </Button>
          )}
        </div>
      ))}
    </fieldset>
  );
}
