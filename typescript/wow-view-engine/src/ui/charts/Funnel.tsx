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

import type { FunnelData } from '../../analysis/index.js';
import { cn } from 'cn';
import type { FamilyProps } from './family.js';

export function Funnel({
  data,
  spec,
  className,
  label,
  name,
}: FamilyProps<FunnelData>) {
  const widest = Math.max(...data.stages.map(stage => stage.value), 1);
  // Stages taken from a group are named by its values, which show as the
  // group's column does; metric stages carry the labels they were given.
  const stages = spec?.funnel?.stages;
  const category = stages?.from === 'group' ? stages.category : undefined;
  // Which metric a stage measures: one for the whole funnel when the stages
  // are values of a dimension, one per stage when each is its own metric.
  const measured = (index: number) =>
    stages === undefined
      ? undefined
      : stages.from === 'group'
        ? stages.value
        : stages.items[index]?.metric;
  const horizontal = spec?.funnel?.orientation === 'horizontal';

  return (
    <div
      data-slot="funnel"
      role="img"
      aria-label={name}
      className={cn(
        'gap-2',
        horizontal ? 'flex items-end' : 'flex flex-col',
        className,
      )}
    >
      {data.stages.map((stage, index) => (
        <div
          key={stage.label}
          className={cn(
            'flex gap-2',
            horizontal ? 'flex-col-reverse' : 'items-center',
          )}
        >
          <span className="text-muted-foreground w-28 shrink-0 truncate text-xs">
            {category === undefined
              ? stage.label
              : label(category, stage.label)}
          </span>
          <div
            className="bg-primary/80 flex h-7 items-center justify-end rounded-sm px-2"
            style={{ width: `${Math.max(4, (stage.value / widest) * 100)}%` }}
          >
            <span className="text-primary-foreground text-xs">
              {label(measured(index), stage.value)}
            </span>
          </div>
          {stage.conversion !== undefined && (
            <span className="text-muted-foreground w-12 shrink-0 text-xs">
              {Math.round(stage.conversion * 100)}%
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
