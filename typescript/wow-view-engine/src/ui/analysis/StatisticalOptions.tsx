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

import { without, type RadarSpec } from '../../model/index.js';
import {
  FieldDescription,
  FieldLegend,
  FieldSet,
} from '../components/field.js';
import { useViewMessages } from '../MessagesProvider.js';
import {
  CheckField,
  ChoiceField,
  HINT,
  NumberField,
  SlotSelect,
  type OptionsPageProps,
} from './optionControls.js';

/** The fewest axes a radar or parallel axes draw (`chart.radar.too-few-metrics`). */
const FEWEST_AXES = 3;

/** A number's formats, as a card's are chosen. */
const FORMATS = ['auto', 'compact', 'percent'] as const;

/**
 * A boxplot's options: which dimension's groups the boxes are, and — when
 * the metrics hold the five numbers of more than one field — whose spread
 * is drawn. The five are never picked one by one: they are one field's,
 * together (`fiveNumberSets`), and a set is named by its median's column.
 */
export function BoxplotSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.boxplot;
  if (!spec) return null;
  const sets = shape.fiveNumbers ?? [];
  const titleOf = (alias: string) =>
    shape.metrics.find(metric => metric.value === alias)?.label ?? alias;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.box')}
        items={shape.groups}
        value={spec.category}
        onChange={category =>
          onChange({ ...chart, boxplot: { ...spec, category } })
        }
      />
      {sets.length > 1 && (
        <SlotSelect
          label={messages.label('label.chart.slot.spread')}
          items={sets.map(set => ({
            value: set.median,
            label: titleOf(set.median),
          }))}
          value={spec.median}
          onChange={median => {
            const set = sets.find(entry => entry.median === median);
            if (set) onChange({ ...chart, boxplot: { ...spec, ...set } });
          }}
        />
      )}
    </>
  );
}

/**
 * A candlestick's options: which date dimension's buckets the candles are,
 * and — when the metrics hold the four numbers of more than one field —
 * whose moves are drawn. The four are never picked one by one: they are one
 * field's, together (`ohlcSets`), and a set is named by its close's column.
 */
export function CandlestickSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.candlestick;
  if (!spec) return null;
  const sets = shape.ohlc ?? [];
  const titleOf = (alias: string) =>
    shape.metrics.find(metric => metric.value === alias)?.label ?? alias;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.candle')}
        items={shape.groups.filter(group => shape.dated?.has(group.value))}
        value={spec.x}
        onChange={x => onChange({ ...chart, candlestick: { ...spec, x } })}
      />
      {sets.length > 1 && (
        <SlotSelect
          label={messages.label('label.chart.slot.moves')}
          items={sets.map(set => ({
            value: set.close,
            label: titleOf(set.close),
          }))}
          value={spec.close}
          onChange={close => {
            const set = sets.find(entry => entry.close === close);
            if (set) onChange({ ...chart, candlestick: { ...spec, ...set } });
          }}
        />
      )}
    </>
  );
}

/** A gauge's needle: the one number it places, a quantity. */
export function GaugeSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.gauge;
  if (!spec) return null;
  return (
    <SlotSelect
      label={messages.label('label.chart.slot.value')}
      items={shape.quantities}
      value={spec.metric}
      onChange={metric => onChange({ ...chart, gauge: { ...spec, metric } })}
    />
  );
}

/**
 * A gauge's scale and its target: where the dial starts and ends — left
 * blank, 0 and a round number past the value and the target — where the
 * number should reach, and how it is written.
 */
export function GaugeDisplay({ chart, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.gauge;
  if (!spec) return null;
  const set = (member: 'target' | 'min' | 'max', value: number | undefined) =>
    onChange({
      ...chart,
      gauge:
        value === undefined
          ? without(spec, member)
          : { ...spec, [member]: value },
    });
  return (
    <>
      <NumberField
        label={messages.label('label.chart.target-value')}
        value={spec.target}
        onChange={target => set('target', target)}
      />
      <NumberField
        label={messages.label('label.chart.gauge.min')}
        hint={
          spec.min === undefined
            ? messages.label('label.chart.gauge.auto')
            : undefined
        }
        value={spec.min}
        onChange={min => set('min', min)}
      />
      <NumberField
        label={messages.label('label.chart.gauge.max')}
        hint={
          spec.max === undefined
            ? messages.label('label.chart.gauge.auto')
            : undefined
        }
        value={spec.max}
        onChange={max => set('max', max)}
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
            gauge:
              format === 'auto' ? without(spec, 'format') : { ...spec, format },
          })
        }
      />
    </>
  );
}

/**
 * A radar's or parallel axes' options: whose groups the shapes or lines
 * are, and which metrics are axes — a box each, in the order the metrics
 * stand; one ticked is added at the end of the axes. The last three stay:
 * fewer axes draw no shape, and the box that would take one away says why.
 */
export function ProfileSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const family = chart.type === 'radar' ? 'radar' : 'parallel';
  const spec = chart[family];
  if (!spec) return null;
  const update = (next: RadarSpec) => onChange({ ...chart, [family]: next });
  const fewest = spec.metrics.length <= FEWEST_AXES;
  return (
    <>
      <SlotSelect
        label={messages.label(
          family === 'radar'
            ? 'label.chart.slot.shape'
            : 'label.chart.slot.line',
        )}
        items={shape.groups}
        value={spec.category}
        onChange={category => update({ ...spec, category })}
      />
      <FieldSet data-slot="chart-axes">
        <FieldLegend variant="label">
          {messages.label('label.chart.slot.axes')}
        </FieldLegend>
        {shape.quantities.map(metric => {
          const on = spec.metrics.includes(metric.value);
          return (
            <CheckField
              key={metric.value}
              label={metric.label}
              checked={on}
              disabled={on && fewest}
              onChange={checked =>
                update({
                  ...spec,
                  metrics: checked
                    ? [...spec.metrics, metric.value]
                    : spec.metrics.filter(alias => alias !== metric.value),
                })
              }
            />
          );
        })}
        <FieldDescription className={HINT}>
          {messages.label('chart.fit.needs-three-metrics')}
        </FieldDescription>
      </FieldSet>
    </>
  );
}
