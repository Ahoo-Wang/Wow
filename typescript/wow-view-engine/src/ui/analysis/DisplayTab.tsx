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

import { PlusIcon, XIcon } from 'lucide-react';
import {
  CHART_FAMILIES,
  isSmooth,
  isStacked,
  withSmooth,
  withStacked,
} from '../../analysis/index.js';
import {
  CHART_COLOR_SLOTS,
  CHART_FAMILY,
  type CartesianSpec,
  type ReferenceLine,
} from '../../model/index.js';
import { without } from '../../model/index.js';
import { Button } from '../components/button.js';
import { NumberInput } from '../FilterValueEditor.js';
import { IconButton } from '../IconButton.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { EditorCard, PillInput } from '../variants.js';
import { CompactSelect } from './CompactSelect.js';
import { useListFocus } from './listFocus.js';
import {
  CheckField,
  ChoiceField,
  NumberField,
  OptionsSection,
  SlotSelect,
  type OptionsPageProps,
} from './optionControls.js';

const LEGENDS = ['auto', 'top', 'bottom', 'right', 'none'] as const;
const FORMATS = ['auto', 'compact', 'percent'] as const;

/**
 * The display page (D20 屏 J): how the marks are drawn, family by family,
 * with the two settings every drawn chart shares — the legend and the value
 * labels — first. Colours are the theme's and have no control here (D20).
 */
export function DisplayTab(props: OptionsPageProps) {
  const messages = useViewMessages();
  const { chart, onChange } = props;
  const family = CHART_FAMILY[chart.type];
  const { legend, labels } = CHART_FAMILIES[family];
  return (
    <>
      {legend && (
        <SlotSelect
          label={messages.label('label.chart.legend')}
          items={LEGENDS.map(where => ({
            value: where,
            label: messages.label(`label.chart.legend.${where}`),
          }))}
          value={chart.legend ?? 'auto'}
          onChange={where => onChange({ ...chart, legend: where })}
        />
      )}
      {labels && (
        <CheckField
          data-slot="chart-labels"
          label={messages.label('label.chart.labels')}
          checked={chart.labels === true}
          onChange={on => onChange({ ...chart, labels: on })}
        />
      )}
      {family === 'cartesian' && <CartesianDisplay {...props} />}
      {family === 'pie' && <PieDisplay {...props} />}
      {family === 'heatmap' && <HeatmapDisplay {...props} />}
      {family === 'funnel' && <FunnelDisplay {...props} />}
      {family === 'metric' && <MetricDisplay {...props} />}
    </>
  );
}

function CartesianDisplay({ chart, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  // A line taken out leaves the keyboard on the line under it, or on
  // 「添加参考线」 once the last one goes (`listFocus.ts`).
  const focus = useListFocus({
    list: '[data-slot="chart-options-reference-lines"]',
    item: '[data-slot="reference-line-card"]',
    add: '[data-slot="add-reference-line"]',
  });
  const spec = chart.cartesian;
  if (!spec) return null;
  const update = (next: CartesianSpec) =>
    onChange({ ...chart, cartesian: next });
  const lines = spec.referenceLines ?? [];
  const setLines = (referenceLines: ReferenceLine[]) =>
    update(
      referenceLines.length === 0
        ? without(spec, 'referenceLines')
        : { ...spec, referenceLines },
    );
  const patchLine = (index: number, change: Partial<ReferenceLine>) =>
    setLines(
      lines.map((line, at) => (at === index ? { ...line, ...change } : line)),
    );
  const curved = chart.type !== 'bar';
  // A right axis exists once a series sits on it; a line hung on an axis
  // with nothing on it is `chart.referenceLine.empty-axis`.
  const axes = new Set(spec.series.map(series => series.axis ?? 'left'));
  // One series stacks against nothing, and a split has not made its series
  // yet — either way the box refuses the press, and a box that refuses
  // without saying why is the analyst's problem rather than the chart's.
  const stackedAlone = spec.series.length <= 1 && spec.splitBy === undefined;
  return (
    <>
      <CheckField
        data-slot="chart-stacked"
        label={messages.label('label.chart.stacked')}
        hint={
          stackedAlone ? messages.label('label.chart.stacked-alone') : undefined
        }
        checked={isStacked(spec)}
        disabled={stackedAlone}
        onChange={on => update(withStacked(spec, on))}
      />
      <CheckField
        data-slot="chart-horizontal"
        label={messages.label('label.chart.horizontal')}
        checked={spec.orientation === 'horizontal'}
        onChange={on =>
          update(
            on
              ? { ...spec, orientation: 'horizontal' }
              : without(spec, 'orientation'),
          )
        }
      />
      {curved && (
        <CheckField
          data-slot="chart-smooth"
          label={messages.label('label.chart.smooth')}
          checked={isSmooth(spec)}
          onChange={on => update(withSmooth(spec, on))}
        />
      )}
      <OptionsSection
        name="reference-lines"
        title={messages.label('label.chart.reference-lines')}
      >
        {lines.map((line, index) => (
          // Axis, value, caption and remove: four controls named after what
          // they are rather than after which line they belong to, and a
          // chart may carry several lines. The card is the group that says
          // which one a reader is standing in.
          <EditorCard
            key={index}
            data-slot="reference-line-card"
            role="group"
            aria-label={messages.label('label.chart.reference-row', {
              index: index + 1,
            })}
            className="flex-nowrap"
          >
            {axes.has('right') && (
              <CompactSelect
                label={messages.label('label.chart.reference-axis')}
                items={(['left', 'right'] as const).map(axis => ({
                  value: axis,
                  label: messages.label(`label.chart.axis.${axis}`),
                }))}
                value={line.axis}
                onChange={axis => patchLine(index, { axis })}
              />
            )}
            <NumberInput
              label={messages.label('label.chart.reference-value')}
              chrome="box"
              className="w-20"
              value={line.value}
              onNumber={value => {
                if (value !== null) patchLine(index, { value });
              }}
            />
            <PillInput
              aria-label={messages.label('label.chart.reference-label')}
              placeholder={messages.label('label.chart.reference-label')}
              chrome="box"
              className="min-w-0 flex-1"
              value={line.label ?? ''}
              onChange={event => {
                const text = event.target.value;
                setLines(
                  lines.map((entry, at) =>
                    at === index
                      ? text === ''
                        ? without(entry, 'label')
                        : { ...entry, label: text }
                      : entry,
                  ),
                );
              }}
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
        ))}
        <Button
          variant="ghost"
          size="sm"
          className="self-start"
          data-slot="add-reference-line"
          onClick={() => setLines([...lines, { axis: 'left', value: 0 }])}
        >
          <PlusIcon data-icon="inline-start" />
          {messages.label('label.chart.add-reference-line')}
        </Button>
      </OptionsSection>
    </>
  );
}

function PieDisplay({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.pie;
  if (!spec) return null;
  return (
    <>
      <CheckField
        data-slot="chart-donut"
        label={messages.label('label.chart.donut')}
        checked={spec.donut === true}
        onChange={on =>
          onChange({
            ...chart,
            pie: on ? { ...spec, donut: true } : without(spec, 'donut'),
          })
        }
      />
      {/*
        The merged tail is a sum, which only an additive metric has. The
        ceiling is the palette's: a slice past it would wear a colour another
        slice already has, and the kernel folds there whatever is written.
      */}
      {shape.additive.has(spec.value) && (
        <NumberField
          label={messages.label('label.chart.max-slices')}
          hint={messages.label('label.chart.max-slices.hint', {
            count: CHART_COLOR_SLOTS,
          })}
          value={spec.maxSlices}
          min={2}
          max={CHART_COLOR_SLOTS}
          onChange={maxSlices =>
            onChange({
              ...chart,
              pie:
                maxSlices === undefined
                  ? without(spec, 'maxSlices')
                  : { ...spec, maxSlices },
            })
          }
        />
      )}
    </>
  );
}

function HeatmapDisplay({ chart, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.heatmap;
  if (!spec) return null;
  return (
    <ChoiceField
      label={messages.label('label.chart.scale')}
      items={(['linear', 'log'] as const).map(scale => ({
        value: scale,
        label: messages.label(`label.chart.scale.${scale}`),
      }))}
      value={spec.scale ?? 'linear'}
      onChange={scale => onChange({ ...chart, heatmap: { ...spec, scale } })}
    />
  );
}

function FunnelDisplay({ chart, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.funnel;
  if (!spec) return null;
  // Only stages that are a group's values accumulate: each object sits in
  // one stage, so "reached at least here" is a sum. Metric stages are each
  // their own count already.
  const stages = spec.stages.from === 'group' ? spec.stages : undefined;
  return (
    <>
      <ChoiceField
        label={messages.label('label.chart.conversion')}
        items={(['previous', 'first', 'none'] as const).map(mode => ({
          value: mode,
          label: messages.label(`label.chart.conversion.${mode}`),
        }))}
        value={spec.conversion ?? 'previous'}
        onChange={conversion =>
          onChange({ ...chart, funnel: { ...spec, conversion } })
        }
      />
      {stages && (
        <CheckField
          data-slot="chart-cumulative"
          label={messages.label('label.chart.cumulative')}
          checked={stages.cumulative !== false}
          onChange={on =>
            onChange({
              ...chart,
              funnel: { ...spec, stages: { ...stages, cumulative: on } },
            })
          }
        />
      )}
      <CheckField
        data-slot="chart-horizontal"
        label={messages.label('label.chart.horizontal')}
        checked={spec.orientation === 'horizontal'}
        onChange={on =>
          onChange({
            ...chart,
            funnel: on
              ? { ...spec, orientation: 'horizontal' }
              : without(spec, 'orientation'),
          })
        }
      />
    </>
  );
}

function MetricDisplay({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.metric;
  // A moment headline is written out as its column reads it: a target to
  // reach and a number format are about quantities (`chart.metric.moment`).
  if (!spec || shape.moments.has(spec.metric)) return null;
  return (
    <>
      <NumberField
        label={messages.label('label.chart.target-value')}
        value={spec.target}
        onChange={target =>
          onChange({
            ...chart,
            metric:
              target === undefined
                ? without(spec, 'target')
                : { ...spec, target },
          })
        }
      />
      <ChoiceField
        label={messages.label('label.chart.format')}
        items={FORMATS.map(format => ({
          value: format,
          label: messages.label(`label.chart.format.${format}`),
        }))}
        value={spec.format ?? 'auto'}
        onChange={format =>
          onChange({
            ...chart,
            metric:
              format === 'auto' ? without(spec, 'format') : { ...spec, format },
          })
        }
      />
    </>
  );
}

/** The one display setting the table has. */
export function TableDisplay({
  totals,
  onTotals,
}: {
  totals: boolean;
  onTotals(on: boolean): void;
}) {
  const messages = useViewMessages();
  return (
    <CheckField
      data-slot="chart-totals"
      label={messages.label('label.analysis.totals')}
      checked={totals}
      onChange={onTotals}
    />
  );
}

/** The number formats, named; the axes page offers the same list. */
export function formatChoices(messages: MessageFormatters) {
  return FORMATS.map(format => ({
    value: format,
    label: messages.label(`label.chart.format.${format}`),
  }));
}
