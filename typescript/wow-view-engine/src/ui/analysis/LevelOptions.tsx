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

import { ArrowDownIcon, ArrowUpIcon } from 'lucide-react';
import { withMoved } from '../../analysis/index.js';
import type { HierarchySpec } from '../../model/index.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { EditorCard } from '../variants.js';
import { useListFocus } from './listFocus.js';
import {
  OptionsSection,
  SlotSelect,
  type OptionsPageProps,
} from './optionControls.js';

/**
 * The data page of a sunburst, a tree or a sankey (D41): its levels, one
 * dimension each, in the order that is the chart's whole meaning — 品类
 * outside 子类, 渠道 before 支付方式 — moved up and down by hand as a
 * funnel's stages are; and the size, listing only the metrics that add up
 * (a part is a share of its parent, a band of what flows through).
 */
export function LevelSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const family = chart.type as 'sunburst' | 'tree' | 'sankey';
  const spec = chart[family];
  // A level that moves keeps the keyboard on the button that moved it.
  const focus = useListFocus({
    list: '[data-slot="chart-options-levels"]',
    item: '[data-slot="level-card"]',
  });
  if (!spec) return null;
  const update = (next: HierarchySpec) =>
    onChange({ ...chart, [family]: next });
  const nameOf = (alias: string) =>
    shape.groups.find(group => group.value === alias)?.label ?? alias;
  return (
    <>
      <OptionsSection
        name="levels"
        title={messages.label(
          family === 'sankey'
            ? 'label.chart.slot.flow'
            : 'label.chart.slot.levels',
        )}
      >
        <ol className="flex flex-col gap-2">
          {spec.levels.map((alias, index) => {
            const name = nameOf(alias);
            return (
              <li key={alias}>
                <EditorCard data-slot="level-card" data-level={alias}>
                  <span className="truncate font-medium" title={name}>
                    {name}
                  </span>
                  <IconButton
                    label={messages.label('label.chart.move-up', { name })}
                    variant="ghost"
                    size="icon-xs"
                    className="ml-auto"
                    data-move="up"
                    disabled={index === 0}
                    onClick={event => {
                      focus.moved(event, index - 1, 'up');
                      update({
                        ...spec,
                        levels: withMoved(spec.levels, index, -1),
                      });
                    }}
                  >
                    <ArrowUpIcon />
                  </IconButton>
                  <IconButton
                    label={messages.label('label.chart.move-down', { name })}
                    variant="ghost"
                    size="icon-xs"
                    data-move="down"
                    disabled={index === spec.levels.length - 1}
                    onClick={event => {
                      focus.moved(event, index + 1, 'down');
                      update({
                        ...spec,
                        levels: withMoved(spec.levels, index, 1),
                      });
                    }}
                  >
                    <ArrowDownIcon />
                  </IconButton>
                </EditorCard>
              </li>
            );
          })}
        </ol>
      </OptionsSection>
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities.filter(metric =>
          shape.additive.has(metric.value),
        )}
        value={spec.value}
        onChange={value => update({ ...spec, value })}
      />
    </>
  );
}

/**
 * A calendar's day dimension — the date buckets by day, which is all a
 * calendar can lay out — and its shade.
 */
export function CalendarSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.calendar;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.day')}
        items={shape.groups.filter(group => shape.daily?.has(group.value))}
        value={spec.date}
        onChange={date => onChange({ ...chart, calendar: { ...spec, date } })}
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities}
        value={spec.value}
        onChange={value => onChange({ ...chart, calendar: { ...spec, value } })}
      />
    </>
  );
}

/**
 * A theme river's time dimension, the one whose values are its streams,
 * and their width — a metric that adds up, since the streams stack.
 */
export function RiverSlots({ chart, shape, onChange }: OptionsPageProps) {
  const messages = useViewMessages();
  const spec = chart.themeRiver;
  if (!spec) return null;
  return (
    <>
      <SlotSelect
        label={messages.label('label.chart.slot.x')}
        items={shape.groups.filter(group => shape.dated?.has(group.value))}
        value={spec.x}
        onChange={x =>
          onChange({
            ...chart,
            themeRiver: {
              ...spec,
              x,
              ...(spec.splitBy === x ? { splitBy: spec.x } : {}),
            },
          })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.streams')}
        items={shape.groups.filter(group => group.value !== spec.x)}
        value={spec.splitBy}
        onChange={splitBy =>
          onChange({ ...chart, themeRiver: { ...spec, splitBy } })
        }
      />
      <SlotSelect
        label={messages.label('label.chart.slot.value')}
        items={shape.quantities.filter(metric =>
          shape.additive.has(metric.value),
        )}
        value={spec.value}
        onChange={value =>
          onChange({ ...chart, themeRiver: { ...spec, value } })
        }
      />
    </>
  );
}
