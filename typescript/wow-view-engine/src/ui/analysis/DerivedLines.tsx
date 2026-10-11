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
import {
  DERIVED_KINDS,
  MAX_MOVING_WINDOW,
  without,
  type DerivedKind,
  type DerivedSeries,
} from '../../model/index.js';
import { useViewMessages } from '../kit/MessagesProvider.js';
import { gapReason } from '../charts/markWords.js';
import { drawnMetrics, type SpecProps } from './cartesianSpec.js';
import {
  CheckField,
  HINT,
  NumberField,
  OptionsSection,
  SlotSelect,
  saidOnce,
} from './optionControls.js';

/**
 * 「算出的线」: a trend, a moving average and a running total of one drawn
 * metric, each a box. A box the result cannot carry is greyed with the
 * reason (`derivedGap`, Q53) — and one already ticked stays pressable, so
 * a saved line can be taken off even while it cannot be drawn.
 */
export function DerivedLines(props: SpecProps) {
  const { chart, spec, onChange, gapOf, defaultWindow } = props;
  const messages = useViewMessages();
  const ids = useId();
  const metrics = drawnMetrics(props);
  const derived = spec.derived ?? [];
  if (metrics.length === 0) return null;
  const metric =
    metrics.find(entry => entry.value === derived[0]?.metric)?.value ??
    metrics[0].value;
  const setDerived = (next: DerivedSeries[]) =>
    onChange({
      ...chart,
      cartesian:
        next.length === 0
          ? without(spec, 'derived')
          : { ...spec, derived: next },
    });
  const entryOf = (kind: DerivedKind) =>
    derived.find(entry => entry.kind === kind);
  // Unticked, a moving average is judged at its narrowest window, so the box
  // opens wherever some window fits; ticked, at its own — and 「点太少」
  // then says to lower the periods.
  const reasons = DERIVED_KINDS.map(kind => {
    const entry = entryOf(kind);
    const gap = gapOf?.({
      kind,
      metric,
      window:
        entry === undefined && kind === 'moving-average' ? 2 : entry?.window,
    });
    return {
      gap,
      reason: gap ? gapReason(messages, { kind, ...gap }) : undefined,
    };
  });
  // Two boxes in a row told 「横轴不是时间」 read as two faults (R2-100).
  const told = saidOnce(
    reasons.map(each => each.reason),
    `${ids}-gap`,
  );
  return (
    <OptionsSection
      name="derived"
      title={messages.label('label.chart.derived')}
    >
      <p className="fve:text-quiet-foreground">
        {messages.label('label.chart.derived.hint')}
      </p>
      {metrics.length > 1 && (
        <SlotSelect
          label={messages.label('label.chart.derived.metric')}
          items={metrics}
          value={metric}
          onChange={next =>
            setDerived(derived.map(entry => ({ ...entry, metric: next })))
          }
        />
      )}
      {DERIVED_KINDS.map((kind, at) => {
        const entry = entryOf(kind);
        const { gap } = reasons[at];
        const said = told[at];
        return (
          <div key={kind} className="fve:flex fve:flex-col fve:gap-2">
            <CheckField
              data-slot={`chart-derived-${kind}`}
              label={messages.label(`label.chart.derived.add.${kind}`)}
              hint={said.hint}
              describedBy={said.describedBy}
              checked={entry !== undefined}
              disabled={gap !== undefined && entry === undefined}
              onChange={on =>
                setDerived(
                  on
                    ? [...derived, { kind, metric }]
                    : derived.filter(one => one.kind !== kind),
                )
              }
            />
            {said.under && (
              <p
                id={said.under.id}
                data-slot="chart-derived-gap"
                className={HINT}
              >
                {said.under.text}
              </p>
            )}
            {kind === 'moving-average' && entry && (
              <NumberField
                label={messages.label('label.chart.derived.window')}
                hint={messages.label('label.chart.derived.window.hint', {
                  count: defaultWindow ?? 3,
                })}
                value={entry.window}
                min={2}
                max={MAX_MOVING_WINDOW}
                onChange={window =>
                  setDerived(
                    derived.map(one =>
                      one.kind !== kind
                        ? one
                        : window === undefined
                          ? without(one, 'window')
                          : { ...one, window },
                    ),
                  )
                }
              />
            )}
          </div>
        );
      })}
    </OptionsSection>
  );
}
