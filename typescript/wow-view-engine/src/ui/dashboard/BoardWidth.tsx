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

import { FoldHorizontalIcon, UnfoldHorizontalIcon } from 'lucide-react';
import { DASHBOARD_WIDTHS, type DashboardWidth } from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { Tooltip, TooltipTrigger } from '../components/tooltip.js';
import type { MessageKey } from '../messages.js';
import { useViewMessages } from '../MessagesProvider.js';
import { TooltipContent } from '../popups.js';

const LABELS = {
  fixed: 'label.dashboard.width-fixed',
  full: 'label.dashboard.width-full',
} as const satisfies Record<DashboardWidth, MessageKey>;

/** Arrows in to a width, arrows out to the edges. */
const ICONS = {
  fixed: FoldHorizontalIcon,
  full: UnfoldHorizontalIcon,
} as const satisfies Record<DashboardWidth, unknown>;

/**
 * 固定宽度／全宽 on the edit bar (D31): the board's width, switched while it
 * is built — one step of building, taken back by 撤销 like any other
 * (`DashboardEditing.setWidth`). A switch with two positions, as the result
 * toolbar's layout switch is: two icons in one outline, each named by its
 * word, the one in force pressed.
 *
 * Not in the one-column reading: below `md` a board is one column whatever
 * its width, so there is nothing there to see it change.
 */
export function BoardWidthSwitch({
  dashboard,
}: {
  dashboard: DashboardController;
}) {
  const messages = useViewMessages();
  return (
    <ToggleGroup
      data-slot="dashboard-width"
      value={[dashboard.width]}
      onValueChange={value => {
        // Pressing the one in force would clear the group; a board always
        // has a width, so only a press on the other one changes it.
        const next = DASHBOARD_WIDTHS.find(width => width === value[0]);
        if (next) dashboard.edit?.setWidth(next);
      }}
      variant="outline"
      size="sm"
      spacing={0}
      aria-label={messages.label('label.dashboard.width')}
    >
      {DASHBOARD_WIDTHS.map(width => {
        const Icon = ICONS[width];
        return (
          <Tooltip key={width}>
            <TooltipTrigger
              render={
                <ToggleGroupItem
                  value={width}
                  aria-label={messages.label(LABELS[width])}
                />
              }
            >
              <Icon />
            </TooltipTrigger>
            <TooltipContent>{messages.label(LABELS[width])}</TooltipContent>
          </Tooltip>
        );
      })}
    </ToggleGroup>
  );
}
