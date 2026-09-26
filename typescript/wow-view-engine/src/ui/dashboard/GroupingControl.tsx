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

import { InfoIcon, XIcon } from 'lucide-react';
import type { AnalysisDateUnit } from '../../model/index.js';
import { Button } from '../components/button.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { IconButton, IconTooltip } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';

/** The time grouping (整板 按日｜周｜月): one choice among the units offered. */
export function GroupingControl({
  units,
  unit,
  idle,
  onChange,
  onRemove,
}: {
  units: readonly AnalysisDateUnit[];
  unit: AnalysisDateUnit;
  idle: boolean;
  onChange(unit: AnalysisDateUnit): void;
  onRemove?(): void;
}) {
  const messages = useViewMessages();
  const name = messages.label('label.filters.grouping');
  return (
    <div
      data-slot="dashboard-grouping"
      data-idle={idle || undefined}
      className="flex shrink-0 items-center gap-1"
    >
      <ToggleGroup
        value={[unit]}
        onValueChange={next => {
          const picked = next[0] as AnalysisDateUnit | undefined;
          if (picked && units.includes(picked)) onChange(picked);
        }}
        variant="outline"
        size="sm"
        spacing={0}
        aria-label={name}
      >
        {units.map(entry => (
          <ToggleGroupItem key={entry} value={entry}>
            {messages.label(`label.date-unit.${entry}`)}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {idle && (
        <IconTooltip
          label={messages.label('label.filters.idle', { filter: name })}
          render={
            <Button
              data-slot="dashboard-filter-idle"
              variant="ghost"
              size="icon-xs"
            />
          }
        >
          <InfoIcon />
        </IconTooltip>
      )}
      {onRemove && (
        <IconButton
          data-slot="dashboard-grouping-remove"
          label={messages.label('label.filters.grouping-remove')}
          variant="ghost"
          size="icon-xs"
          onClick={onRemove}
        >
          <XIcon />
        </IconButton>
      )}
    </div>
  );
}
