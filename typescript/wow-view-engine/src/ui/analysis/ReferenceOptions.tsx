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

import { useId } from 'react';
import { PlusIcon, XIcon } from 'lucide-react';
import {
  isPercentStacked,
  stacks,
  type DerivedGap,
} from '../../analysis/index.js';
import {
  REFERENCE_STATISTICS,
  without,
  type ReferenceBand,
  type ReferenceLine,
} from '../../model/index.js';
import { Button } from '../components/button.js';
import { NumberInput } from '../filter/FilterValueEditor.js';
import { IconButton } from '../kit/IconButton.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { gapReason } from '../charts/markWords.js';
import { EditorCard } from '../kit/variants.js';
import { CompactSelect } from './CompactSelect.js';
import { useListFocus } from './listFocus.js';
import {
  CheckField,
  type OptionsPageProps,
  OptionsSection,
  ReferenceLabel,
} from './optionControls.js';
import { drawnMetrics, type SpecProps } from './cartesianSpec.js';
import { DerivedLines } from './DerivedLines.js';

/** Where a reference line stands: a number, or a statistic of a metric. */
type LineKind = 'value' | (typeof REFERENCE_STATISTICS)[number];

/**
 * What the display page draws over a cartesian chart's marks (batch B):
 * reference lines — at a number, the average or the median of a drawn
 * metric — target bands, the highest and lowest points, and the lines
 * computed from a metric along the time axis. Every number is the kernel's
 * (`placeLines`, `derivedGap`); a choice the result cannot carry is greyed
 * with the reason, the one sentence the chart says above itself when a
 * saved view asks for it anyway (Q53).
 */
export function ReferenceOptions(props: OptionsPageProps) {
  const spec = props.chart.cartesian;
  if (!spec) return null;
  return (
    <>
      <ReferenceLines {...props} spec={spec} />
      <ReferenceBands {...props} spec={spec} />
      <Extremes {...props} spec={spec} />
      <DerivedLines {...props} spec={spec} />
    </>
  );
}

function ReferenceLines(props: SpecProps) {
  const { chart, spec, onChange } = props;
  const messages = useViewMessages();
  // A line taken out leaves the keyboard on the line under it, or on
  // 「添加参考线」 once the last one goes (`listFocus.ts`).
  const focus = useListFocus({
    list: '[data-slot="chart-options-reference-lines"]',
    item: '[data-slot="reference-line-card"]',
    add: '[data-slot="add-reference-line"]',
  });
  const lines = spec.referenceLines ?? [];
  const metrics = drawnMetrics(props);
  const setLines = (referenceLines: ReferenceLine[]) =>
    onChange({
      ...chart,
      cartesian:
        referenceLines.length === 0
          ? without(spec, 'referenceLines')
          : { ...spec, referenceLines },
    });
  const setLine = (index: number, line: ReferenceLine) =>
    setLines(lines.map((entry, at) => (at === index ? line : entry)));
  // A right axis exists once a series sits on it; a line hung on an axis
  // with nothing on it is `chart.referenceLine.empty-axis`.
  const axes = new Set(metrics.map(metric => metric.axis));
  // One statistic line over a split could not say which value's series it
  // measures, and over shares it stands at no height it means: offered
  // greyed, with the reason (`placeLines`).
  const statisticGap: DerivedGap | undefined =
    spec.splitBy !== undefined
      ? 'split'
      : isPercentStacked(spec, chart.type)
        ? 'shares'
        : undefined;
  const kinds: { value: LineKind; label: string }[] = [
    {
      value: 'value',
      label: messages.label('label.chart.reference-kind.value'),
    },
    ...(statisticGap || metrics.length === 0
      ? []
      : REFERENCE_STATISTICS.map(of => ({
          value: of,
          label: messages.label(`label.chart.reference-kind.${of}`),
        }))),
  ];
  return (
    <OptionsSection
      name="reference-lines"
      title={messages.label('label.chart.reference-lines')}
    >
      {statisticGap && (
        <p
          data-slot="reference-statistic-gap"
          className="fve:text-quiet-foreground"
        >
          {gapReason(messages, { kind: 'average', gap: statisticGap })}
        </p>
      )}
      {lines.map((line, index) => {
        const kind: LineKind = line.statistic ?? 'value';
        // Switching what the line stands at keeps its axis and its caption;
        // a statistic starts on the first metric drawn on that axis.
        const toKind = (next: LineKind) => {
          const kept = without(
            without(without(line, 'value'), 'statistic'),
            'metric',
          );
          if (next === 'value') return setLine(index, { ...kept, value: 0 });
          const metric =
            metrics.find(entry => entry.value === line.metric) ??
            metrics.find(entry => entry.axis === line.axis) ??
            metrics[0];
          setLine(index, {
            ...kept,
            statistic: next,
            metric: metric.value,
            axis: metric.axis,
          });
        };
        return (
          // Each control named after what it is, the card saying which line
          // a reader is standing in.
          <EditorCard
            key={index}
            data-slot="reference-line-card"
            role="group"
            aria-label={messages.label('label.chart.reference-row', {
              index: index + 1,
            })}
          >
            <CompactSelect
              label={messages.label('label.chart.reference-kind')}
              items={
                kinds.some(item => item.value === kind)
                  ? kinds
                  : [
                      ...kinds,
                      {
                        value: kind,
                        label: messages.label(
                          `label.chart.reference-kind.${kind}`,
                        ),
                      },
                    ]
              }
              value={kind}
              onChange={toKind}
            />
            {line.statistic === undefined ? (
              <>
                {axes.has('right') && (
                  <CompactSelect
                    label={messages.label('label.chart.reference-axis')}
                    items={(['left', 'right'] as const).map(axis => ({
                      value: axis,
                      label: messages.label(`label.chart.axis.${axis}`),
                    }))}
                    value={line.axis}
                    onChange={axis => setLine(index, { ...line, axis })}
                  />
                )}
                <NumberInput
                  label={messages.label('label.chart.reference-value')}
                  chrome="box"
                  className="fve:w-20"
                  value={line.value}
                  onNumber={value => {
                    if (value !== null) setLine(index, { ...line, value });
                  }}
                />
              </>
            ) : (
              metrics.length > 1 && (
                <CompactSelect
                  label={messages.label('label.chart.reference-metric')}
                  items={metrics}
                  value={line.metric ?? metrics[0].value}
                  onChange={metric =>
                    setLine(index, {
                      ...line,
                      metric,
                      axis:
                        metrics.find(entry => entry.value === metric)?.axis ??
                        'left',
                    })
                  }
                />
              )
            )}
            <ReferenceLabel
              value={line.label}
              onChange={text =>
                setLine(
                  index,
                  text === undefined
                    ? without(line, 'label')
                    : { ...line, label: text },
                )
              }
            />
            <IconButton
              label={messages.label('label.chart.remove-reference-line')}
              variant="ghost"
              size="icon-xs"
              onClick={event => {
                focus.removing(event, index);
                setLines(lines.filter((_line, at) => at !== index));
              }}
            >
              <XIcon />
            </IconButton>
          </EditorCard>
        );
      })}
      <Button
        variant="ghost"
        size="sm"
        className="fve:self-start"
        data-slot="add-reference-line"
        onClick={() => setLines([...lines, { axis: 'left', value: 0 }])}
      >
        <PlusIcon data-icon="inline-start" />
        {messages.label('label.chart.add-reference-line')}
      </Button>
    </OptionsSection>
  );
}

/**
 * Target bands: from one number up to another on one axis. A new band
 * starts at the span of the first metric's values on screen, so it is
 * visible the moment it is added and the analyst narrows it from there.
 */
function ReferenceBands(props: SpecProps) {
  const { chart, spec, rows, onChange } = props;
  const messages = useViewMessages();
  const focus = useListFocus({
    list: '[data-slot="chart-options-reference-bands"]',
    item: '[data-slot="reference-band-card"]',
    add: '[data-slot="add-reference-band"]',
  });
  const bands = spec.referenceBands ?? [];
  const orderId = useId();
  const axes = new Set(drawnMetrics(props).map(metric => metric.axis));
  const setBands = (referenceBands: ReferenceBand[]) =>
    onChange({
      ...chart,
      cartesian:
        referenceBands.length === 0
          ? without(spec, 'referenceBands')
          : { ...spec, referenceBands },
    });
  const setBand = (index: number, band: ReferenceBand) =>
    setBands(bands.map((entry, at) => (at === index ? band : entry)));
  const first = spec.series[0]?.metric;
  const values = rows
    .map(row => (first === undefined ? undefined : row[first]))
    .filter((value): value is number => typeof value === 'number');
  const fresh: ReferenceBand = {
    axis: spec.series[0]?.axis ?? 'left',
    from: values.length > 0 ? Math.min(0, ...values) : 0,
    to: values.length > 0 ? Math.max(...values) : 1,
  };
  return (
    <OptionsSection
      name="reference-bands"
      title={messages.label('label.chart.reference-bands')}
    >
      {bands.map((band, index) => {
        const ordered = band.from < band.to;
        return (
          <EditorCard
            key={index}
            data-slot="reference-band-card"
            data-invalid={ordered ? undefined : true}
            role="group"
            aria-label={messages.label('label.chart.reference-band-row', {
              index: index + 1,
            })}
          >
            {axes.has('right') && (
              <CompactSelect
                label={messages.label('label.chart.reference-axis')}
                items={(['left', 'right'] as const).map(axis => ({
                  value: axis,
                  label: messages.label(`label.chart.axis.${axis}`),
                }))}
                value={band.axis}
                onChange={axis => setBand(index, { ...band, axis })}
              />
            )}
            <NumberInput
              label={messages.label('label.chart.band-from')}
              chrome="box"
              className="fve:w-20"
              value={band.from}
              onNumber={from => {
                if (from !== null) setBand(index, { ...band, from });
              }}
            />
            {/* The two ends read as one span, 「1,000 ～ 1,400」; each box is
                named 从／到 for whoever does not see the mark. */}
            <span aria-hidden className="fve:text-muted-foreground">
              ～
            </span>
            <NumberInput
              label={messages.label('label.chart.band-to')}
              chrome="box"
              className="fve:w-20"
              value={band.to}
              invalid={!ordered}
              describedBy={ordered ? undefined : `${orderId}-${index}`}
              onNumber={to => {
                if (to !== null) setBand(index, { ...band, to });
              }}
            />
            <ReferenceLabel
              value={band.label}
              onChange={text =>
                setBand(
                  index,
                  text === undefined
                    ? without(band, 'label')
                    : { ...band, label: text },
                )
              }
            />
            <IconButton
              label={messages.label('label.chart.remove-reference-band')}
              variant="ghost"
              size="icon-xs"
              onClick={event => {
                focus.removing(event, index);
                setBands(bands.filter((_band, at) => at !== index));
              }}
            >
              <XIcon />
            </IconButton>
            {!ordered && (
              <p
                id={`${orderId}-${index}`}
                role="alert"
                className="fve:text-destructive fve:basis-full"
              >
                {messages.label('label.chart.band-order')}
              </p>
            )}
          </EditorCard>
        );
      })}
      <Button
        variant="ghost"
        size="sm"
        className="fve:self-start"
        data-slot="add-reference-band"
        onClick={() => setBands([...bands, fresh])}
      >
        <PlusIcon data-icon="inline-start" />
        {messages.label('label.chart.add-reference-band')}
      </Button>
    </OptionsSection>
  );
}

/**
 * 「标出最高点与最低点」: a segment of a stack stands at its stack's height,
 * so it is not marked (`extremesOf`); a chart whose every series stacks
 * has nothing to mark, and the box says why rather than doing nothing.
 */
function Extremes({ chart, spec, onChange }: SpecProps) {
  const messages = useViewMessages();
  const stacking =
    isPercentStacked(spec, chart.type) ||
    spec.series.some(
      series => series.stack !== undefined && stacks(chart.type, series),
    );
  const alone =
    stacking &&
    spec.series.every(
      series => series.stack !== undefined && stacks(chart.type, series),
    );
  const on = spec.extremes === true;
  return (
    <CheckField
      data-slot="chart-extremes"
      label={messages.label('label.chart.extremes')}
      hint={
        stacking ? messages.label('label.chart.extremes.stacked') : undefined
      }
      checked={on}
      disabled={alone && !on}
      onChange={next =>
        onChange({
          ...chart,
          cartesian: next
            ? { ...spec, extremes: true }
            : without(spec, 'extremes'),
        })
      }
    />
  );
}
