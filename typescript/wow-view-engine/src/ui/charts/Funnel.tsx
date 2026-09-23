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

import { Fragment } from 'react';
import type { FunnelData } from '../../analysis/index.js';
import { cn } from 'cn';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { formatValue } from './axis.js';
import { conversionHeading, stageName, type FamilyProps } from './family.js';
import { color } from './palette.js';

/**
 * A funnel, drawn by hand: one bar per stage, as long as the stage is
 * against the widest, its number beside it and its conversion after that.
 *
 * - **The bar wears the palette's first slot** (`color(0)`), as a cartesian
 *   chart's one series does — a funnel is one series of stages. It used to
 *   be `primary`, the colour the theme gives a button, not data.
 * - **Numbers are text beside the bar, not on it.** Text wears the text
 *   tokens: a number printed on a slot colour has whatever contrast that
 *   slot happens to have against white.
 * - **The percentages are said to be conversion rates, and of what**
 *   (`conversionHeading`): the kernel divides each stage by the one before
 *   it unless the spec asks for the first, and 「25%」 alone reads as a share
 *   of the whole. The heading sits over their column, once.
 * - The columns are one grid, so every bar starts at the same edge and the
 *   widest ends before the numbers rather than under them; the workbench
 *   gives the grid the result's 16px gutter (`styles.css`).
 */
export function Funnel({
  data,
  spec,
  className,
  label,
  column,
  name,
}: FamilyProps<FunnelData>) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const widest = Math.max(...data.stages.map(stage => stage.value), 1);
  // Stages taken from a group are named by its values, which show as the
  // group's column does; metric stages carry the labels they were given,
  // and fall back to the metric's column title (`stageName`).
  const stages = spec?.funnel?.stages;
  // Which metric a stage measures: one for the whole funnel when the stages
  // are values of a dimension, one per stage when each is its own metric.
  const measured = (index: number) =>
    stages === undefined
      ? undefined
      : stages.from === 'group'
        ? stages.value
        : stages.items[index]?.metric;
  const horizontal = spec?.funnel?.orientation === 'horizontal';
  const converts = data.stages.some(stage => stage.conversion !== undefined);
  const heading = converts && (
    <span
      data-slot="funnel-conversion-heading"
      className={cn(
        'text-muted-foreground text-right',
        horizontal ? 'self-end' : 'col-start-4',
      )}
    >
      {messages.label(conversionHeading(spec?.funnel?.conversion))}
    </span>
  );
  const drawn = data.stages.map((stage, index) => ({
    key: `${index}:${stage.label}`,
    name: stageName(stages, index, stage.label, label, column),
    value: label(measured(index), stage.value),
    // A stage of nothing keeps a sliver, so it reads as a bar of zero rather
    // than as a row whose bar failed to draw.
    share: `${Math.max(2, (stage.value / widest) * 100)}%`,
    conversion:
      stage.conversion === undefined
        ? ''
        : formatValue(stage.conversion, 'percent', locale),
  }));
  const bar = (share: string, along: 'width' | 'height') => (
    <div
      data-slot="funnel-bar"
      className={cn('rounded-sm', along === 'width' ? 'h-7' : 'w-full')}
      style={{ [along]: share, background: color(0) }}
    />
  );

  if (horizontal)
    return (
      <div
        data-slot="funnel"
        role="img"
        aria-label={name}
        className={cn('flex flex-col gap-2 pb-3 text-xs', className)}
      >
        {heading}
        <div className="flex items-stretch gap-3">
          {drawn.map(stage => (
            <div
              key={stage.key}
              data-slot="funnel-stage"
              className="flex min-w-0 flex-1 flex-col items-center gap-1"
            >
              <span className="text-foreground tabular-nums">
                {stage.value}
              </span>
              {/* The stage grows up from a common floor. */}
              <div className="flex h-40 w-full flex-col justify-end">
                {bar(stage.share, 'height')}
              </div>
              <span
                className="text-muted-foreground w-full truncate text-center"
                title={stage.name}
              >
                {stage.name}
              </span>
              {converts && (
                <span className="text-muted-foreground tabular-nums">
                  {stage.conversion}
                </span>
              )}
            </div>
          ))}
        </div>
      </div>
    );

  return (
    <div
      data-slot="funnel"
      role="img"
      aria-label={name}
      className={cn(
        'grid items-center gap-x-3 gap-y-2 pb-3 text-xs',
        converts
          ? 'grid-cols-[minmax(0,7rem)_minmax(0,1fr)_auto_auto]'
          : 'grid-cols-[minmax(0,7rem)_minmax(0,1fr)_auto]',
        className,
      )}
    >
      {heading}
      {drawn.map(stage => (
        <Fragment key={stage.key}>
          <span
            data-slot="funnel-stage"
            className="text-muted-foreground truncate"
            title={stage.name}
          >
            {stage.name}
          </span>
          <div className="min-w-0">{bar(stage.share, 'width')}</div>
          <span className="text-foreground text-right tabular-nums">
            {stage.value}
          </span>
          {converts && (
            <span className="text-muted-foreground text-right tabular-nums">
              {stage.conversion}
            </span>
          )}
        </Fragment>
      ))}
    </div>
  );
}
