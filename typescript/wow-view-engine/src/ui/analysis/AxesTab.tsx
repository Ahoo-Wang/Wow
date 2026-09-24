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

import { without } from '../../model/index.js';
import type { AxisSpec, CartesianSpec } from '../../model/index.js';
import { measuredTitle } from '../charts/axis.js';
import { useViewMessages } from '../MessagesProvider.js';
import { formatChoices } from './DisplayTab.js';
import {
  ChoiceField,
  NumberField,
  OptionsSection,
  TextField,
  type OptionsPageProps,
} from './optionControls.js';

/**
 * The axes page (D20 屏 J), for the cartesian family only: the numeric
 * axes' title, bounds and number format. The right axis is a page section
 * once a series sits on it, and not before — an axis with nothing on it is
 * not drawn. The category axis has nothing to set: it says what the
 * dimension says.
 */
export function AxesTab({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.cartesian;
  if (!spec) return null;
  const sides = spec.series.some(series => series.axis === 'right')
    ? (['left', 'right'] as const)
    : (['left'] as const);
  // The title the chart draws while the box is empty, said in the box: the
  // column the axis measures, the several it measures on a chart of two
  // axes, or — two on the one axis — that the legend names them.
  const titles = new Map(
    shape.metrics.map(metric => [metric.value, metric.label]),
  );
  const drawnTitle = (side: 'left' | 'right') =>
    measuredTitle(
      spec.series
        .filter(series => (series.axis ?? 'left') === side)
        .map(series => series.metric),
      sides.length > 1,
      alias => (alias === undefined ? undefined : titles.get(alias)),
      messages.label('label.filter.join'),
    ) ?? messages.label('label.chart.axis-title.none');
  const update = (side: 'left' | 'right', axis: AxisSpec | undefined) => {
    const yAxis =
      axis === undefined
        ? without(spec.yAxis ?? {}, side)
        : { ...spec.yAxis, [side]: axis };
    const next: CartesianSpec =
      Object.keys(yAxis).length === 0
        ? without(spec, 'yAxis')
        : { ...spec, yAxis };
    onChange({ ...chart, cartesian: next });
  };
  return (
    <>
      {sides.map(side => {
        const axis = spec.yAxis?.[side] ?? {};
        const set = <K extends keyof AxisSpec>(key: K, value: AxisSpec[K]) => {
          const next: AxisSpec =
            value === undefined
              ? without(axis, key)
              : { ...axis, [key]: value };
          update(side, Object.keys(next).length === 0 ? undefined : next);
        };
        return (
          <OptionsSection
            key={side}
            name={`axis-${side}`}
            title={messages.label(`label.chart.axis.${side}`)}
          >
            <TextField
              label={messages.label('label.chart.axis-title')}
              placeholder={drawnTitle(side)}
              value={axis.label}
              onChange={label => set('label', label)}
            />
            <NumberField
              label={messages.label('label.chart.axis-min')}
              value={axis.min}
              onChange={min => set('min', min)}
            />
            <NumberField
              label={messages.label('label.chart.axis-max')}
              value={axis.max}
              onChange={max => set('max', max)}
            />
            <ChoiceField
              label={messages.label('label.chart.format')}
              items={formatChoices(messages)}
              value={axis.format ?? 'auto'}
              onChange={format =>
                set('format', format === 'auto' ? undefined : format)
              }
            />
          </OptionsSection>
        );
      })}
    </>
  );
}
