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

import { GaugeDisplay } from './StatisticalOptions.js';
import {
  CHART_FAMILIES,
  PEAKS_ONLY_FROM,
  chartMarks,
  isPercentStacked,
  isSmooth,
  isStacked,
  offersPercentStack,
  offersStacking,
  peaksOnlyLabels,
  stacks,
  valueLabelsOn,
  withPercentStack,
  withSmooth,
  withStacked,
} from '../../analysis/index.js';
import {
  CARTESIAN_MISSING,
  CHART_COLOR_SLOTS,
  CHART_FAMILY,
  type CartesianSpec,
} from '../../model/index.js';
import { without } from '../../model/index.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../MessagesProvider.js';
import { drawsHorizontal } from '../charts/cartesianPlan.js';
import { Field, FieldDescription } from '../components/field.js';
import { WaterfallDisplay } from './CompositionOptions.js';
import { ReferenceOptions } from './ReferenceOptions.js';
import {
  CheckField,
  ChoiceField,
  HINT,
  NumberField,
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
      {labels && <ValueLabelsField {...props} />}
      {family === 'cartesian' && <CartesianDisplay {...props} />}
      {family === 'pie' && <PieDisplay {...props} />}
      {family === 'heatmap' && <HeatmapDisplay {...props} />}
      {family === 'funnel' && <FunnelDisplay {...props} />}
      {family === 'metric' && <MetricDisplay {...props} />}
      {family === 'waterfall' && <WaterfallDisplay {...props} />}
      {family === 'gauge' && <GaugeDisplay {...props} />}
    </>
  );
}

/**
 * The value labels, as the chart draws them (Storybook review P1-6
 * follow-up). Where the automatic choice depends on the result — upright
 * bars that stand on their own write every number up to `PEAKS_ONLY_FROM`
 * of them and only the highest and the lowest past it — three choices:
 * 「自动」 (`labels` unset, said under the choices for this chart), 「每个
 * 都标」 (`true`) and 「不标」 (`false`). A tick box read ticked there
 * while only two bars had a number, and every number took an untick and a
 * tick.
 *
 * Anywhere else the automatic choice is one of the other two whatever the
 * rows — a line, an area and a heatmap write none, a waterfall, bars lying
 * on their side and stacked bars write every one — so a third button would
 * repeat one of them: two choices, each written as the value it names. A
 * pie always writes each slice's share, and the choice is whether its value
 * goes with it (「只标占比」, 「数值与占比」).
 */
function ValueLabelsField({
  chart,
  shape,
  rows,
  label,
  data,
  onChange,
}: OptionsPageProps) {
  const messages = useViewMessages();
  const on = valueLabelsOn(chart);
  // Unset is 「自动」; `undefined` takes the member out (`updateChart`).
  const set = (next: boolean | undefined) =>
    onChange({ ...chart, labels: next });
  if (CHART_FAMILY[chart.type] === 'pie')
    return (
      <ChoiceField
        data-slot="chart-labels"
        label={messages.label('label.chart.labels')}
        items={[
          { value: 'share', label: messages.label('label.chart.labels.share') },
          {
            value: 'value-share',
            label: messages.label('label.chart.labels.value-share'),
          },
        ]}
        value={on ? 'value-share' : 'share'}
        onChange={choice => set(choice === 'value-share')}
      />
    );
  const spec = chart.cartesian;
  const marks = chartMarks(chart);
  const stackable =
    spec?.series.filter(series => stacks(chart.type, series)) ?? [];
  // Whether the bars may write only their peak and trough: upright, and
  // standing on their own rather than in a stack of more than one.
  const peaks =
    spec !== undefined &&
    CHART_FAMILY[chart.type] === 'cartesian' &&
    marks.includes('bar') &&
    !drawsHorizontal(
      chart,
      rows.map(row => label(spec.x, row[spec.x])),
      shape.dated?.has(spec.x) === true,
    ) &&
    !(
      isStacked(spec, chart.type) &&
      (stackable.length > 1 || spec.splitBy !== undefined)
    );
  if (!peaks)
    return (
      <ChoiceField
        data-slot="chart-labels"
        label={messages.label('label.chart.labels')}
        items={(['all', 'none'] as const).map(choice => ({
          value: choice,
          label: messages.label(`label.chart.labels.${choice}`),
        }))}
        value={on ? 'all' : 'none'}
        onChange={choice => set(choice === 'all')}
      />
    );
  const choice =
    chart.labels === undefined ? 'auto' : chart.labels ? 'all' : 'none';
  // What 「自动」 does here: the bars on screen, and whether they are past
  // the count — found by the kernel only where they are.
  const count = data?.type === 'cartesian' ? data.points.length : rows.length;
  const found =
    data?.type !== 'cartesian' || Object.keys(data.extremes ?? {}).length > 0;
  const lines = marks.some(mark => mark !== 'bar') ? '.lines' : '';
  const hint =
    peaksOnlyLabels({ ...chart, labels: undefined }, count) && found
      ? messages.label(`label.chart.labels.auto.peaks${lines}`, { count })
      : messages.label(`label.chart.labels.auto.bars${lines}`, {
          from: PEAKS_ONLY_FROM,
        });
  return (
    <ChoiceField
      data-slot="chart-labels"
      label={messages.label('label.chart.labels')}
      hint={choice === 'auto' ? hint : undefined}
      items={(['auto', 'all', 'none'] as const).map(value => ({
        value,
        label: messages.label(`label.chart.labels.${value}`),
      }))}
      value={choice}
      onChange={next => set(next === 'auto' ? undefined : next === 'all')}
    />
  );
}

function CartesianDisplay(props: OptionsPageProps) {
  const { chart, shape, rows, label, onChange, data } = props;
  const messages = useViewMessages();
  const spec = chart.cartesian;
  if (!spec) return null;
  // A split past the palette that could not fold its rest into 「其他」 — a
  // metric that does not add up — is drawn whole, its colours repeating;
  // the page says so and what reads it better (D33 Q56).
  const crowded = data?.type === 'cartesian' && data.crowded === true;
  const update = (next: CartesianSpec) =>
    onChange({ ...chart, cartesian: next });
  const curved = chart.type !== 'bar';
  // A stored value this package does not know reads as the default.
  const missing = spec.missing === 'gap' ? 'gap' : 'zero';
  // Only bars and areas stack (`stacks`): a line chart has no such box, and
  // a combo counts only its bars and areas. One of them stacks against
  // nothing, and a split has not made its series yet — either way the box
  // refuses the press, and a box that refuses without saying why is the
  // analyst's problem rather than the chart's.
  const stackable = spec.series.filter(series => stacks(chart.type, series));
  const stackedAlone =
    stackable.length === 0 ||
    (stackable.length === 1 && spec.splitBy === undefined);
  return (
    <>
      {/* A hint line, toned as every hint on this panel is (`HINT`): the
          muted grey falls under 4.5:1 on the sidebar's ground. */}
      {crowded && (
        <Field>
          <FieldDescription data-slot="chart-crowded" className={HINT}>
            {messages.label('label.chart.crowded', {
              slots: CHART_COLOR_SLOTS,
            })}
          </FieldDescription>
        </Field>
      )}
      {offersStacking(chart.type) && (
        <CheckField
          data-slot="chart-stacked"
          label={messages.label('label.chart.stacked')}
          hint={
            stackedAlone
              ? messages.label('label.chart.stacked-alone')
              : undefined
          }
          checked={isStacked(spec, chart.type)}
          disabled={stackedAlone}
          onChange={on => update(withStacked(spec, on, chart.type))}
        />
      )}
      {/*
        Shares need a whole: offered only on bars and areas whose every
        metric adds up, with a split or two series to share it
        (`offersPercentStack`); anywhere else the box is not there, because
        no setting of this chart could make it mean something.
      */}
      {offersPercentStack(spec, chart.type, shape.additive) && (
        <CheckField
          data-slot="chart-percent-stack"
          label={messages.label('label.chart.percent-stack')}
          hint={messages.label('label.chart.percent-stack.hint')}
          checked={isPercentStacked(spec, chart.type)}
          onChange={on => update(withPercentStack(spec, on, chart.type))}
        />
      )}
      {/*
        Ticked when the chart is drawn on its side: because the analyst said
        so, or — said nothing — because its names are too long to stand under
        their bars (`drawsHorizontal`). A press says it either way, so a
        chart the rule laid down can be stood back up.
      */}
      <CheckField
        data-slot="chart-horizontal"
        label={messages.label('label.chart.horizontal')}
        checked={drawsHorizontal(
          chart,
          rows.map(row => label(spec.x, row[spec.x])),
          shape.dated?.has(spec.x) === true,
        )}
        onChange={on =>
          update({ ...spec, orientation: on ? 'horizontal' : 'vertical' })
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
      {/*
        What a point the rows lack draws as. The default is the kernel's
        rule, said under the choice so 「补 0」 is not read as "every hole is
        0": an average, or a group 「只保留」 dropped, is never made one.
      */}
      <ChoiceField
        data-slot="chart-missing"
        label={messages.label('label.chart.missing')}
        hint={messages.label(`label.chart.missing.${missing}.hint`)}
        items={CARTESIAN_MISSING.map(mode => ({
          value: mode,
          label: messages.label(`label.chart.missing.${mode}`),
        }))}
        value={missing}
        onChange={mode =>
          update(
            mode === 'gap'
              ? { ...spec, missing: 'gap' }
              : without(spec, 'missing'),
          )
        }
      />
      <ReferenceOptions {...props} />
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
      {stages && (
        <CheckField
          data-slot="chart-cumulative"
          label={messages.label('label.chart.cumulative')}
          checked={stages.cumulative === true}
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
  const trend = spec.trend;
  return (
    <>
      {/*
        Which way is good colours every change the card draws: against the
        previous period, which only the last-period reading draws, and
        against the metric it is compared with.
      */}
      {((trend && trend.headline !== 'whole') || spec.compare) && (
        <CheckField
          data-slot="metric-lower-is-better"
          label={messages.label('label.chart.lower-is-better')}
          hint={messages.label('label.chart.lower-is-better.hint')}
          checked={spec.lowerIsBetter === true}
          onChange={on =>
            onChange({
              ...chart,
              metric: on
                ? { ...spec, lowerIsBetter: true }
                : without(spec, 'lowerIsBetter'),
            })
          }
        />
      )}
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
  whole,
  onTotals,
}: {
  totals: boolean;
  /**
   * The question has no dimension: its one row is every record in the
   * range, so there is no totals row to draw (`AnalysisView.totals`). The
   * box stays, greyed and saying why, because the choice comes back with
   * the first dimension.
   */
  whole?: boolean;
  onTotals(on: boolean): void;
}) {
  const messages = useViewMessages();
  return (
    <CheckField
      data-slot="chart-totals"
      label={messages.label('label.analysis.totals')}
      checked={totals && !whole}
      disabled={whole}
      {...(whole
        ? { hint: messages.label('label.analysis.totals-whole') }
        : {})}
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
