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
  EXPRESSION_OPERATORS,
  OPERATOR_SIGN,
  derivedText,
  DEFAULT_PERCENTILE,
  expressionText,
  isDuration,
  isFormula,
} from '../../analysis/index.js';
import {
  DERIVED_FORMAT_STYLES,
  MAX_DERIVED_DECIMALS,
  isDateCell,
  isValueMetric,
  without,
  type DerivedFormat,
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type AnalysisExpressionOperator,
  type AnalysisFunction,
  type AnalysisMetric,
} from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { Input } from '../components/input.js';
import { NumberInput } from '../FilterValueEditor.js';
import { useViewMessages } from '../MessagesProvider.js';
import { CompactSelect } from './CompactSelect.js';
import { metricReference } from './editing.js';

/** The word the operand select shows for "a number typed here". */
const NUMBER = '#number';

/**
 * One operation over two operands, as the formula card and the derived
 * card both edit it (D20 屏 B): each operand is picked from a list — a
 * field for a formula, an earlier metric for a derived metric — or typed
 * as a number; the operator sits between. Nesting is not offered here; a
 * nested expression a config holds is shown as its text and left alone.
 */
function BinaryEditor<E extends { type: string }>({
  name,
  left,
  right,
  operator,
  choices,
  disabled,
  operand,
  read,
  text,
  onChange,
}: {
  name: string;
  left: E;
  right: E;
  operator: AnalysisExpressionOperator;
  /** What an operand may be picked from. */
  choices: readonly { value: string; label: string }[];
  disabled?: boolean;
  /** Builds an operand from a pick, or from a typed number. */
  operand(pick: string | number): E;
  /** Reads an operand back: a pick's value, a number, or null for a nested one. */
  read(operand: E): string | number | null;
  /** A nested operand said as its author would, since no control holds it. */
  text(operand: E): string;
  onChange(left: E, operator: AnalysisExpressionOperator, right: E): void;
}) {
  const messages = useViewMessages();
  const items = [
    ...choices,
    { value: NUMBER, label: messages.label('label.analysis.operand-number') },
  ];
  const side = (
    which: 'left' | 'right',
    value: E,
    write: (next: E) => void,
  ) => {
    const current = read(value);
    // An operand deeper than one operation has no control here. Drawing
    // nothing left the card half-written and the other half editable, which
    // read as a formula over one operand; the text stands in its place, and
    // the note below says why it cannot be touched.
    if (current === null)
      return (
        <span data-slot="operand-text" className="text-muted-foreground">
          {text(value)}
        </span>
      );
    const label = messages.label(
      which === 'left'
        ? 'label.analysis.operand-left'
        : 'label.analysis.operand-right',
      { name },
    );
    return (
      <>
        <CompactSelect
          label={label}
          items={items}
          value={typeof current === 'number' ? NUMBER : current}
          disabled={disabled}
          onChange={pick => write(operand(pick === NUMBER ? 0 : pick))}
        />
        {typeof current === 'number' && (
          <NumberInput
            label={messages.label('label.analysis.operand-value', { name })}
            chrome="box"
            className="w-20"
            disabled={disabled}
            value={current}
            onNumber={next => {
              if (next !== null) write(operand(next));
            }}
          />
        )}
      </>
    );
  };
  return (
    <>
      {side('left', left, next => onChange(next, operator, right))}
      <CompactSelect
        label={messages.label('label.analysis.operator', { name })}
        items={EXPRESSION_OPERATORS.map(op => ({
          value: op,
          label: OPERATOR_SIGN[op],
        }))}
        value={operator}
        disabled={disabled}
        onChange={op => onChange(left, op, right)}
      />
      {side('right', right, next => onChange(left, operator, next))}
      {(read(left) === null || read(right) === null) && (
        <span
          data-slot="expression-unreadable"
          className="text-muted-foreground w-full"
        >
          {messages.label('label.analysis.expression-unreadable')}
        </span>
      )}
    </>
  );
}

/**
 * The controls of a formula metric: the two fields (or numbers), the
 * operation, and how the result is summarised across the group.
 */
export function FormulaControls({
  analysis,
  metric,
  index,
  name,
  disabled,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  name: string;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  if (!isFormula(metric)) return null;
  // A date is no operand (`analysis.expression.date-operand`): its earliest
  // and its latest are summaries of it, and arithmetic over it is not.
  const fields = analysis.fields
    .filter(
      field =>
        field.functions.length > 0 &&
        field.expressionInput !== false &&
        !isDateCell(field.cell),
    )
    .map(field => ({ value: field.field, label: field.label }));
  const functions = (
    ['SUM', 'AVG', 'MIN', 'MAX'] as const satisfies readonly AnalysisFunction[]
  ).map(fn => ({ value: fn, label: messages.label(`label.summary.fn.${fn}`) }));
  return (
    <>
      <BinaryEditor<AnalysisExpression>
        name={name}
        left={metric.expression.left}
        right={metric.expression.right}
        operator={metric.expression.operator}
        choices={fields}
        disabled={disabled}
        operand={pick =>
          typeof pick === 'number'
            ? { type: 'CONSTANT', value: pick }
            : { type: 'FIELD', field: pick }
        }
        read={operand =>
          operand.type === 'FIELD'
            ? operand.field
            : operand.type === 'CONSTANT'
              ? operand.value
              : null
        }
        text={operand =>
          expressionText(
            operand,
            field =>
              analysis.fields.find(entry => entry.field === field)?.label ??
              field,
          )
        }
        onChange={(left, operator, right) =>
          analysis.updateMetric(index, {
            expression: { type: 'BINARY', operator, left, right },
          })
        }
      />
      <CompactSelect
        label={messages.label('label.analysis.function-of', { name })}
        items={functions}
        value={metric.function}
        disabled={disabled}
        onChange={fn => analysis.updateMetric(index, { function: fn })}
      />
    </>
  );
}

/**
 * The times a duration may run between (N3): the counting unit's fields
 * that it buckets by date and takes into arithmetic — what Wow asks of each
 * end of a `DATE_DIFF`.
 */
export function durationFields(
  analysis: Pick<AnalysisEditorController, 'fields'>,
): { value: string; label: string }[] {
  return analysis.fields
    .filter(
      field =>
        field.groups.includes('DATE_HISTOGRAM') &&
        field.expressionInput !== false,
    )
    .map(field => ({ value: field.field, label: field.label }));
}

/** How a duration may be summarised across the group, in the order offered. */
const DURATION_SUMMARIES = ['AVG', 'MIN', 'MAX', 'SUM', 'PERCENTILE'] as const;

/**
 * The controls of a duration (N3, 「两个时刻之差」): from which time to which,
 * in which unit, and how the durations are summarised across the group — an
 * average, a lowest, a highest, a total or a percentile (with its number).
 */
export function DurationControls({
  analysis,
  metric,
  index,
  name,
  disabled,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  name: string;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  if (!isDuration(metric)) return null;
  const { expression } = metric;
  const times = durationFields(analysis);
  const write = (patch: Partial<typeof expression>) =>
    analysis.updateMetric(index, { expression: { ...expression, ...patch } });
  const summary = metric.type === 'PERCENTILE' ? 'PERCENTILE' : metric.function;
  return (
    <>
      <CompactSelect
        label={messages.label('label.analysis.duration-from', { name })}
        items={times}
        value={expression.from}
        disabled={disabled}
        onChange={from => write({ from })}
      />
      <span aria-hidden="true" className="text-muted-foreground">
        →
      </span>
      <CompactSelect
        label={messages.label('label.analysis.duration-to', { name })}
        items={times}
        value={expression.to}
        disabled={disabled}
        onChange={to => write({ to })}
      />
      <CompactSelect
        label={messages.label('label.analysis.duration-unit', { name })}
        items={(analysis.dateDiffUnits.includes(expression.unit)
          ? analysis.dateDiffUnits
          : [expression.unit, ...analysis.dateDiffUnits]
        ).map(unit => ({
          value: unit,
          label: messages.label(`label.date-diff-unit.${unit}`),
        }))}
        value={expression.unit}
        disabled={disabled}
        onChange={unit => write({ unit: unit })}
      />
      <CompactSelect
        label={messages.label('label.analysis.function-of', { name })}
        items={DURATION_SUMMARIES.map(fn => ({
          value: fn,
          label: messages.label(`label.summary.fn.${fn}`),
        }))}
        value={summary}
        disabled={disabled}
        onChange={next =>
          analysis.replaceMetric(
            index,
            next === 'PERCENTILE'
              ? {
                  ...without(metric, 'function' as never),
                  type: 'PERCENTILE',
                  percentile: DEFAULT_PERCENTILE,
                  expression,
                }
              : {
                  ...without(metric, 'percentile' as never),
                  type: 'NUMERIC',
                  function: next,
                  expression,
                },
          )
        }
      />
    </>
  );
}

/**
 * The controls of a derived metric: two metrics before it (or numbers)
 * and the operation. A sample value cannot be read — Wow refuses it.
 */
export function DerivedControls({
  analysis,
  metric,
  index,
  name,
  disabled,
}: {
  analysis: AnalysisEditorController;
  metric: AnalysisMetric;
  index: number;
  name: string;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  if (metric.type !== 'DERIVED') return null;
  const formatControls = (
    <DerivedFormatControls
      analysis={analysis}
      metric={metric}
      index={index}
      name={name}
      disabled={disabled}
    />
  );
  if (metric.expression.type !== 'BINARY') return formatControls;
  const earlier = analysis.metrics
    .slice(0, index)
    .filter(
      entry => !isValueMetric(entry) && !analysis.moments.has(entry.alias),
    )
    .map(entry => ({
      value: entry.alias,
      label: metricReference(analysis, entry, messages),
    }));
  return (
    <>
      <BinaryEditor<AnalysisDerivedExpression>
        name={name}
        left={metric.expression.left}
        right={metric.expression.right}
        operator={metric.expression.operator}
        choices={earlier}
        disabled={disabled}
        operand={pick =>
          typeof pick === 'number'
            ? { type: 'CONSTANT', value: pick }
            : { type: 'METRIC_REF', metric: pick }
        }
        read={operand =>
          operand.type === 'METRIC_REF'
            ? operand.metric
            : operand.type === 'CONSTANT'
              ? operand.value
              : null
        }
        text={operand =>
          derivedText(operand, alias => {
            const referenced = analysis.metrics.find(
              entry => entry.alias === alias,
            );
            return referenced
              ? metricReference(analysis, referenced, messages)
              : alias;
          })
        }
        onChange={(left, operator, right) =>
          analysis.updateMetric(index, {
            expression: { type: 'BINARY', operator, left, right },
          })
        }
      />
      {formatControls}
    </>
  );
}

type Derived = Extract<AnalysisMetric, { type: 'DERIVED' }>;

/** The style a derived metric reads as; a plain number when it says none. */
const styleOf = (metric: Derived): DerivedFormat['style'] =>
  metric.format?.style ?? 'number';

/**
 * How a derived metric's number reads (D38): as a number, a percent or
 * money, with so many decimals, in a currency — which, left blank, is the
 * one its operands are in. A plain number with the default decimals is no
 * format at all, so the metric is stored as a fresh one would be.
 */
function DerivedFormatControls({
  analysis,
  metric,
  index,
  name,
  disabled,
}: {
  analysis: AnalysisEditorController;
  metric: Derived;
  index: number;
  name: string;
  disabled?: boolean;
}) {
  const messages = useViewMessages();
  const style = styleOf(metric);
  const write = (format: DerivedFormat) =>
    analysis.replaceMetric(
      index,
      format.style === 'number' && format.decimals === undefined
        ? without(metric, 'format')
        : { ...metric, format },
    );
  // A switch of style keeps the decimals the analyst typed; a currency only
  // money has.
  const decimals = metric.format?.decimals;
  const currency =
    metric.format?.style === 'currency' ? metric.format.currency : undefined;
  const styled = (next: DerivedFormat['style']): DerivedFormat => ({
    style: next,
    ...(decimals === undefined ? {} : { decimals }),
    ...(next === 'currency' && currency !== undefined ? { currency } : {}),
  });
  return (
    <>
      <CompactSelect
        label={messages.label('label.analysis.derived.style-of', { name })}
        items={DERIVED_FORMAT_STYLES.map(value => ({
          value,
          label: messages.label(`label.analysis.derived.style.${value}`),
        }))}
        value={style}
        disabled={disabled}
        onChange={next => write(styled(next))}
      />
      <NumberInput
        label={messages.label('label.analysis.derived.decimals', { name })}
        chrome="box"
        className="w-16"
        disabled={disabled}
        value={decimals}
        placeholder={String(style === 'percent' ? 1 : 2)}
        onNumber={next => {
          const base = styled(style);
          if (next === null) {
            write(without(base, 'decimals'));
            return;
          }
          if (
            Number.isInteger(next) &&
            next >= 0 &&
            next <= MAX_DERIVED_DECIMALS
          )
            write({ ...base, decimals: next });
        }}
      />
      {style === 'currency' && (
        <Input
          data-slot="derived-currency"
          aria-label={messages.label('label.analysis.derived.currency', {
            name,
          })}
          placeholder={messages.label(
            'label.analysis.derived.currency-inherited',
          )}
          className="h-7 w-24 uppercase"
          maxLength={3}
          disabled={disabled}
          defaultValue={currency ?? ''}
          onBlur={event => {
            const code = event.currentTarget.value.trim().toUpperCase();
            const money = {
              style: 'currency' as const,
              ...(decimals === undefined ? {} : { decimals }),
            };
            if (code === (currency ?? '')) return;
            write(code === '' ? money : { ...money, currency: code });
          }}
        />
      )}
    </>
  );
}
