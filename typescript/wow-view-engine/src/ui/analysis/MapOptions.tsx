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

import { useChartMaps } from '../charts/maps.js';
import { useViewMessages } from '../MessagesProvider.js';
import { SlotSelect, type OptionsPageProps } from './optionControls.js';

/**
 * A map's options (D41): the dimension whose values are its regions, the
 * number that shades them, and — where the host registered more than one —
 * which map; left unchosen, the first one registered.
 */
export function MapSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const maps = useChartMaps();
  const spec = chart.map;
  if (!spec) return null;
  return (
    <>
      {maps.length > 1 && (
        <SlotSelect
          label={messages.label('label.chart.slot.map')}
          items={maps.map(map => ({
            value: map.name,
            label: map.label ?? map.name,
          }))}
          value={spec.map ?? maps[0].name}
          onChange={map => onChange({ ...chart, map: { ...spec, map } })}
        />
      )}
      <SlotSelect
        label={messages.label('label.chart.slot.region')}
        items={shape.groups}
        value={spec.region}
        onChange={region => onChange({ ...chart, map: { ...spec, region } })}
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities}
        value={spec.value}
        onChange={value => onChange({ ...chart, map: { ...spec, value } })}
      />
    </>
  );
}
