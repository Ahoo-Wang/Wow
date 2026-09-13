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
  HavingExpressionType as H,
  ComparisonOperator as C,
} from '@ahoo-wang/fetcher-wow';
import { Button } from '../components/ui/button.js';
import { Input } from '../components/ui/input.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import { cloneSnapshot, type DeepReadonly } from '../lib/types.js';
import type {
  AnalysisComponentConfig,
  AnalysisHavingExpression,
} from './analysisModel.js';
import type { FilterValidationError } from '../filter/filterModel.js';
interface Props {
  value?: DeepReadonly<AnalysisHavingExpression>;
  metrics: DeepReadonly<readonly AnalysisComponentConfig[]>;
  onChange(value: AnalysisHavingExpression | undefined): void;
  disabled?: boolean;
  errors?: readonly FilterValidationError[];
  depth?: number;
}
const types = [
  { value: H.CONDITION, label: '比较' },
  { value: H.BETWEEN, label: '区间' },
  { value: H.IN, label: '属于集合' },
  { value: H.IS_NULL, label: '空值' },
  { value: H.AND, label: '全部条件' },
  { value: H.OR, label: '任一条件' },
];
export function AnalysisHavingEditor({
  value,
  metrics,
  onChange,
  disabled,
  errors = [],
  depth = 1,
}: Props) {
  const change = (next: DeepReadonly<AnalysisHavingExpression> | undefined) => {
    if (!disabled)
      onChange(
        next === undefined
          ? undefined
          : cloneSnapshot<AnalysisHavingExpression>(next),
      );
  };
  const create = (): AnalysisHavingExpression => ({
    id: crypto.randomUUID(),
    type: H.CONDITION,
    metricId: metrics[0]?.id ?? '',
    operator: C.GTE,
    value: '',
  });
  if (depth > 8) return <p role="alert">结果筛选最多 8 层。</p>;
  if (!value)
    return (
      <Button
        disabled={disabled}
        variant="outline"
        onClick={() => change(create())}
      >
        添加结果筛选
      </Button>
    );
  const label = `结果筛选 ${value.id}`;
  const metricId = 'metricId' in value ? value.metricId : '';
  const grouped = 'operands' in value;
  const number = (
    name: string,
    text: number | string,
    update: (text: string) => void,
  ) => (
    <label className="fve:flex fve:flex-col fve:gap-1">
      {name}
      <Input
        aria-label={`${label} ${name}`}
        aria-invalid={errors.some(e => e.id === value.id)}
        inputMode="decimal"
        disabled={disabled}
        value={text}
        onChange={e => {
          if (!disabled) update(e.target.value);
        }}
      />
    </label>
  );
  return (
    <fieldset
      disabled={disabled}
      className="fve:flex fve:min-w-0 fve:flex-col fve:gap-2 fve:rounded-lg fve:border fve:p-3"
    >
      <legend>{depth === 1 ? '结果筛选' : '条件组'}</legend>
      <FilterSelect
        label={`${label} 类型`}
        value={value.type}
        disabled={disabled}
        options={types.filter(
          t => depth < 8 || (t.value !== H.AND && t.value !== H.OR),
        )}
        onValueChange={type => {
          const base = { id: value.id, metricId };
          change(
            type === H.CONDITION
              ? { ...base, type, operator: C.GTE, value: '' }
              : type === H.BETWEEN
                ? { ...base, type, lower: '', upper: '' }
                : type === H.IN
                  ? { ...base, type, values: [''] }
                  : type === H.IS_NULL
                    ? { ...base, type }
                    : { id: value.id, type, operands: [create()] },
          );
        }}
      />
      {!grouped && (
        <FilterSelect
          label={`${label} 指标`}
          value={metricId}
          disabled={disabled}
          options={metrics.map(m => ({ value: m.id, label: m.title }))}
          onValueChange={id => change({ ...value, metricId: id })}
        />
      )}
      {!grouped && !metrics.some(m => m.id === metricId) && (
        <p role="alert">筛选指标已失效，请重新选择。</p>
      )}
      {value.type === H.CONDITION && (
        <>
          <FilterSelect
            label={`${label} 比较符`}
            disabled={disabled}
            value={value.operator}
            options={[
              { value: C.EQ, label: '等于' },
              { value: C.NE, label: '不等于' },
              { value: C.GT, label: '大于' },
              { value: C.GTE, label: '大于等于' },
              { value: C.LT, label: '小于' },
              { value: C.LTE, label: '小于等于' },
            ]}
            onValueChange={operator => change({ ...value, operator })}
          />
          {number('数值', value.value, text =>
            change({ ...value, value: text }),
          )}
        </>
      )}
      {value.type === H.BETWEEN && (
        <>
          {number('下界', value.lower, lower => change({ ...value, lower }))}
          {number('上界', value.upper, upper => change({ ...value, upper }))}
        </>
      )}
      {value.type === H.IN && (
        <>
          {value.values.map((text, index) => (
            <div key={index} className="fve:flex fve:items-end fve:gap-2">
              {number(`值 ${index + 1}`, text, next =>
                change({
                  ...value,
                  values: value.values.map((v, i) => (i === index ? next : v)),
                }),
              )}
              <Button
                disabled={disabled}
                variant="ghost"
                aria-label={`${label} 删除值 ${index + 1}`}
                onClick={() =>
                  change({
                    ...value,
                    values: value.values.filter((_, i) => i !== index),
                  })
                }
              >
                删除
              </Button>
            </div>
          ))}
          <Button
            disabled={disabled}
            variant="outline"
            onClick={() => change({ ...value, values: [...value.values, ''] })}
          >
            添加值
          </Button>
        </>
      )}
      {value.type === H.IS_NULL && (
        <FilterSelect
          label={`${label} 空值`}
          disabled={disabled}
          value={value.negated ? 'not-null' : 'null'}
          options={[
            { value: 'null', label: '无值' },
            { value: 'not-null', label: '有值' },
          ]}
          onValueChange={v => change({ ...value, negated: v === 'not-null' })}
        />
      )}
      {grouped && (
        <>
          {value.operands.map((child, index) => (
            <AnalysisHavingEditor
              key={child.id}
              value={child}
              metrics={metrics}
              disabled={disabled}
              depth={depth + 1}
              errors={errors}
              onChange={next =>
                change({
                  ...value,
                  operands: next
                    ? value.operands.map((v, i) => (i === index ? next : v))
                    : value.operands.filter((_, i) => i !== index),
                })
              }
            />
          ))}
          <Button
            disabled={disabled}
            variant="outline"
            onClick={() =>
              change({ ...value, operands: [...value.operands, create()] })
            }
          >
            添加条件
          </Button>
        </>
      )}
      {errors
        .filter(error => error.id === value.id)
        .map((error, index) => (
          <p role="alert" key={index}>
            {error.message}
          </p>
        ))}
      <Button
        disabled={disabled}
        variant="ghost"
        aria-label={`删除${label}`}
        onClick={() => change(undefined)}
      >
        删除条件
      </Button>
    </fieldset>
  );
}
