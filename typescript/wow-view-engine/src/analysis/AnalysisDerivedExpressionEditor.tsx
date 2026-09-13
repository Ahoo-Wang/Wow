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
  DerivedExpressionType as D,
  AggregationExpressionOperator as O,
} from '@ahoo-wang/fetcher-wow';
import { Button } from '../components/ui/button.js';
import { Input } from '../components/ui/input.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisComponentConfig,
  AnalysisDerivedExpression,
} from './analysisModel.js';
interface Props {
  value: DeepReadonly<AnalysisDerivedExpression>;
  metrics: DeepReadonly<readonly AnalysisComponentConfig[]>;
  onChange(value: AnalysisDerivedExpression): void;
  label: string;
  disabled?: boolean;
  depth?: number;
}
export function AnalysisDerivedExpressionEditor({
  value,
  metrics,
  onChange,
  label,
  disabled,
  depth = 1,
}: Props) {
  if (depth > 8) return <p role="alert">公式最多 8 层，请在上层简化。</p>;
  const change = (next: AnalysisDerivedExpression) => {
    if (!disabled) onChange(next);
  };
  if (!value || typeof value !== 'object')
    return (
      <Button
        disabled={disabled}
        variant="outline"
        onClick={() => change({ type: D.CONSTANT, value: '' })}
      >
        修复{label}
      </Button>
    );
  return (
    <fieldset
      disabled={disabled}
      className="fve:flex fve:min-w-0 fve:flex-col fve:gap-2 fve:border-l fve:pl-2"
    >
      <legend className="fve:sr-only">{label}</legend>
      <FilterSelect
        label={`${label} 类型`}
        value={value.type}
        disabled={disabled}
        options={[
          { value: D.METRIC_REF, label: '指标' },
          { value: D.CONSTANT, label: '常量' },
          ...(depth < 8 ? [{ value: D.BINARY, label: '运算' }] : []),
        ]}
        onValueChange={type =>
          change(
            type === D.METRIC_REF
              ? { type, metricId: metrics[0]?.id ?? '' }
              : type === D.CONSTANT
                ? { type, value: '' }
                : {
                    type,
                    operator: O.ADD,
                    left: {
                      type: D.METRIC_REF,
                      metricId: metrics[0]?.id ?? '',
                    },
                    right: { type: D.CONSTANT, value: '' },
                  },
          )
        }
      />
      {value.type === D.METRIC_REF && (
        <FilterSelect
          label={`${label} 指标`}
          value={value.metricId}
          disabled={disabled}
          options={metrics.map(m => ({ value: m.id, label: m.title }))}
          onValueChange={metricId => change({ type: D.METRIC_REF, metricId })}
        />
      )}
      {value.type === D.METRIC_REF &&
        !metrics.some(m => m.id === value.metricId) && (
          <p role="alert">引用指标已失效或不在前面，请重新选择。</p>
        )}
      {value.type === D.CONSTANT && (
        <Input
          aria-label={`${label} 常量`}
          disabled={disabled}
          inputMode="decimal"
          value={value.value}
          onChange={e => change({ type: D.CONSTANT, value: e.target.value })}
        />
      )}
      {value.type === D.BINARY && (
        <>
          <FilterSelect
            label={`${label} 运算符`}
            value={value.operator}
            disabled={disabled}
            options={[
              { value: O.ADD, label: '加 ＋' },
              { value: O.SUBTRACT, label: '减 −' },
              { value: O.MULTIPLY, label: '乘 ×' },
              { value: O.DIVIDE, label: '除 ÷' },
            ]}
            onValueChange={operator => change({ ...value, operator })}
          />
          <AnalysisDerivedExpressionEditor
            value={value.left}
            metrics={metrics}
            label={`${label} 左侧`}
            disabled={disabled}
            depth={depth + 1}
            onChange={left => change({ ...value, left })}
          />
          <AnalysisDerivedExpressionEditor
            value={value.right}
            metrics={metrics}
            label={`${label} 右侧`}
            disabled={disabled}
            depth={depth + 1}
            onChange={right => change({ ...value, right })}
          />
        </>
      )}
    </fieldset>
  );
}
