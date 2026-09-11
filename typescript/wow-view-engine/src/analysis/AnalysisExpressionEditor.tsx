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
  AggregationExpressionType as Type,
  AggregationExpressionOperator as Operator,
  type AggregationFunction,
} from '@ahoo-wang/fetcher-wow';
import { Button } from '../components/ui/button.js';
import { Input } from '../components/ui/input.js';
import { FilterSelect } from '../filter/FilterSelect.js';
import type { DeepReadonly } from '../lib/types.js';
import type {
  AnalysisNumericExpression,
  AnalysisCompileContext,
} from './analysisModel.js';
export interface AnalysisExpressionEditorProps {
  value: DeepReadonly<AnalysisNumericExpression>;
  context: AnalysisCompileContext;
  onChange(value: AnalysisNumericExpression): void;
  label: string;
  disabled?: boolean;
  function?: AggregationFunction;
  depth?: number;
}
const operators = [
  { value: Operator.ADD, label: '加 ＋' },
  { value: Operator.SUBTRACT, label: '减 −' },
  { value: Operator.MULTIPLY, label: '乘 ×' },
  { value: Operator.DIVIDE, label: '除 ÷' },
];
/** Structural editor only: preserves raw constants and never evaluates formulas. */
export function AnalysisExpressionEditor({
  value,
  context,
  onChange,
  label,
  disabled,
  function: fn,
  depth = 1,
}: AnalysisExpressionEditorProps) {
  if (depth > 8) return <p role="alert">公式最多 8 层，请在上层简化。</p>;
  if (!value || typeof value !== 'object')
    return (
      <Button
        variant="outline"
        disabled={disabled}
        aria-label={`修复${label}`}
        onClick={() => {
          if (!disabled) onChange({ type: Type.CONSTANT, value: 0 });
        }}
      >
        修复公式操作数
      </Button>
    );
  const fields = context.fields.filter(
    field =>
      field.type === 'number' &&
      context.capability.fields.some(
        cap =>
          cap.field === field.field &&
          (fn ? cap.functions.includes(fn) : cap.functions.length),
      ),
  );
  return (
    <fieldset
      disabled={disabled}
      className="fve:min-w-0 fve:space-y-2 fve:border-l fve:pl-2"
    >
      <legend className="fve:sr-only">{label}</legend>
      <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
        <FilterSelect
          label={`${label} 类型`}
          value={value.type}
          disabled={disabled}
          options={[
            { value: Type.FIELD, label: '字段' },
            { value: Type.CONSTANT, label: '常量' },
            ...(depth < 8 ? [{ value: Type.BINARY, label: '运算' }] : []),
          ]}
          onValueChange={type => {
            if (disabled) return;
            onChange(
              type === Type.FIELD
                ? { type, field: fields[0]?.field ?? '' }
                : type === Type.CONSTANT
                  ? { type, value: '' }
                  : {
                      type,
                      operator: Operator.ADD,
                      left: { type: Type.FIELD, field: fields[0]?.field ?? '' },
                      right: { type: Type.CONSTANT, value: '' },
                    },
            );
          }}
        />
        {value.type === Type.FIELD && (
          <FilterSelect
            label={`${label} 字段`}
            value={value.field}
            disabled={disabled}
            options={fields.map(field => ({
              value: field.field,
              label: `${field.label}${context.capability.fields.find(cap => cap.field === field.field)?.unit ? ` (${context.capability.fields.find(cap => cap.field === field.field)!.unit})` : ''}`,
            }))}
            onValueChange={field => {
              if (!disabled) onChange({ type: Type.FIELD, field });
            }}
          />
        )}
        {value.type === Type.CONSTANT && (
          <Input
            aria-label={`${label} 常量`}
            className="fve:w-32"
            inputMode="decimal"
            value={value.value}
            disabled={disabled}
            onChange={event => {
              if (!disabled)
                onChange({ type: Type.CONSTANT, value: event.target.value });
            }}
          />
        )}
        {value.type === Type.BINARY && (
          <FilterSelect
            label={`${label} 运算符`}
            value={value.operator}
            options={operators}
            disabled={disabled}
            onValueChange={operator => {
              if (!disabled) onChange({ ...value, operator });
            }}
          />
        )}
      </div>
      {value.type === Type.BINARY && (
        <details open>
          <summary className="fve:cursor-pointer fve:text-xs">
            左右操作数
          </summary>
          <div className="fve:mt-2 fve:space-y-2">
            <AnalysisExpressionEditor
              value={value.left}
              context={context}
              label={`${label} 左侧`}
              function={fn}
              disabled={disabled}
              depth={depth + 1}
              onChange={left => onChange({ ...value, left })}
            />
            <AnalysisExpressionEditor
              value={value.right}
              context={context}
              label={`${label} 右侧`}
              function={fn}
              disabled={disabled}
              depth={depth + 1}
              onChange={right => onChange({ ...value, right })}
            />
          </div>
        </details>
      )}
    </fieldset>
  );
}
