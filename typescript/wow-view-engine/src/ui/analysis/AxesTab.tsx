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

import { cartesianAxisValues, logScaleFits } from '../../analysis/logScale.js';
import { without } from '../../model/index.js';
import type {
  AxisSpec,
  CartesianSpec,
  ScatterSpec,
} from '../../model/index.js';
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
 * The axes page (D20 屏 J), for the cartesian family and the scatter: each
 * numeric axis's title, bounds, number format and scale. A cartesian
 * chart's right axis is a page section once a series sits on it, and not
 * before — an axis with nothing on it is not drawn; its category axis has
 * nothing to set: it says what the dimension says. A scatter measures a
 * metric along each of its two axes, and both are set here.
 */
export function AxesTab(props: OptionsPageProps) {
  const { chart } = props;
  if (chart.cartesian) return <CartesianAxes {...props} />;
  if (chart.type === 'scatter' && chart.scatter)
    return <ScatterAxes {...props} scatter={chart.scatter} />;
  return null;
}

function CartesianAxes({ chart, shape, onChange, data }: OptionsPageProps) {
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
  const cartesian = data?.type === 'cartesian' ? data : undefined;
  return (
    <>
      {sides.map(side => (
        <AxisSection
          key={side}
          name={`axis-${side}`}
          title={messages.label(`label.chart.axis.${side}`)}
          placeholder={drawnTitle(side)}
          axis={spec.yAxis?.[side]}
          logFits={
            !cartesian ||
            logScaleFits(cartesianAxisValues(cartesian, spec, side))
          }
          onChange={axis => update(side, axis)}
        />
      ))}
    </>
  );
}

function ScatterAxes({
  chart,
  shape,
  onChange,
  data,
  scatter,
}: OptionsPageProps & { scatter: ScatterSpec }) {
  const messages = useViewMessages();
  const titles = new Map(
    shape.metrics.map(metric => [metric.value, metric.label]),
  );
  const points = data?.type === 'scatter' ? data.points : undefined;
  const update = (which: 'x' | 'y', axis: AxisSpec | undefined) => {
    const member = which === 'x' ? 'xAxis' : 'yAxis';
    const next: ScatterSpec =
      axis === undefined
        ? without(scatter, member)
        : { ...scatter, [member]: axis };
    onChange({ ...chart, scatter: next });
  };
  return (
    <>
      {(['x', 'y'] as const).map(which => (
        <AxisSection
          key={which}
          name={`axis-${which}`}
          title={messages.label(`label.chart.axis.${which}`)}
          placeholder={
            titles.get(scatter[which]) ??
            messages.label(`label.chart.column.${which}`)
          }
          axis={which === 'x' ? scatter.xAxis : scatter.yAxis}
          logFits={!points || logScaleFits(points.map(point => point[which]))}
          onChange={axis => update(which, axis)}
        />
      ))}
    </>
  );
}

/**
 * One numeric axis's settings: its title, bounds, number format and scale.
 * A log scale over numbers that hold 0 or a negative one is greyed, and the
 * line under the choice says why (D33 batch E); one already chosen stays
 * chosen, so the analyst sees what the view asks and can put it back.
 */
function AxisSection({
  name,
  title,
  placeholder,
  axis = {},
  logFits,
  onChange,
}: {
  name: string;
  title: string;
  /** The title the chart draws while the box is empty. */
  placeholder: string;
  axis?: AxisSpec;
  /** Whether the numbers on screen leave room for a log scale. */
  logFits: boolean;
  onChange(axis: AxisSpec | undefined): void;
}) {
  const messages = useViewMessages();
  const set = <K extends keyof AxisSpec>(key: K, value: AxisSpec[K]) => {
    const next: AxisSpec =
      value === undefined ? without(axis, key) : { ...axis, [key]: value };
    onChange(Object.keys(next).length === 0 ? undefined : next);
  };
  const scale = axis.scale === 'log' ? 'log' : 'linear';
  return (
    <OptionsSection name={name} title={title}>
      <TextField
        label={messages.label('label.chart.axis-title')}
        placeholder={placeholder}
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
      <ChoiceField
        data-slot="axis-scale"
        label={messages.label('label.chart.axis-scale')}
        items={[
          {
            value: 'linear',
            label: messages.label('label.chart.axis-scale.linear'),
          },
          {
            value: 'log',
            label: messages.label('label.chart.axis-scale.log'),
            disabled: !logFits && scale !== 'log',
          },
        ]}
        value={scale}
        {...(logFits
          ? {}
          : { hint: messages.label('label.chart.axis-scale.not-positive') })}
        onChange={next => set('scale', next === 'log' ? 'log' : undefined)}
      />
    </OptionsSection>
  );
}
