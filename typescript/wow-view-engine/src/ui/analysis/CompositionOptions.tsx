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

import { withSlot } from '../../analysis/index.js';
import { useViewMessages } from '../MessagesProvider.js';
import {
  CheckField,
  SlotSelect,
  type OptionsPageProps,
} from './optionControls.js';

/**
 * The options of the two charts that add their numbers up — a waterfall
 * into a running total, a treemap into a whole (D33 Q55). Their measure
 * slot lists only the metrics that add up (a record count or a sum), as a
 * funnel's does: another would be a slot the chart then refuses
 * (`chart.waterfall.not-additive`, `chart.treemap.not-additive`).
 */
export function WaterfallSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.waterfall;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.steps')}
        items={shape.groups}
        value={spec.x}
        onChange={x => onChange({ ...chart, waterfall: { ...spec, x } })}
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities.filter(metric =>
          shape.additive.has(metric.value),
        )}
        value={spec.value}
        onChange={value =>
          onChange({ ...chart, waterfall: { ...spec, value } })
        }
      />
    </>
  );
}

/** Whether the closing total is drawn: the steps' sum, from zero. */
export function WaterfallDisplay({ chart, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.waterfall;
  if (!spec) return null;
  return (
    <CheckField
      data-slot="chart-waterfall-total"
      label={messages.label('label.chart.waterfall.total')}
      checked={spec.total !== false}
      onChange={on => onChange({ ...chart, waterfall: { ...spec, total: on } })}
    />
  );
}

/**
 * A treemap's tiles, and — with a second dimension — the outer level they
 * nest in. Choosing for one level what the other holds swaps them, as a
 * heatmap's two axes do (`withSlot`).
 */
export function TreemapSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.treemap;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.tiles')}
        items={shape.groups}
        value={spec.category}
        onChange={alias =>
          onChange({
            ...chart,
            treemap: withSlot(spec, 'category', 'parent', alias),
          })
        }
      />
      {spec.parent !== undefined && (
        <SlotSelect
          label={messages.label('label.chart.slot.parent')}
          items={shape.groups}
          value={spec.parent}
          onChange={alias =>
            onChange({
              ...chart,
              treemap: withSlot(spec, 'parent', 'category', alias),
            })
          }
        />
      )}
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities.filter(metric =>
          shape.additive.has(metric.value),
        )}
        value={spec.value}
        onChange={value => onChange({ ...chart, treemap: { ...spec, value } })}
      />
    </>
  );
}
