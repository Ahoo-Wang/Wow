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

import { useState, type ReactNode } from 'react';
import type { DashboardField } from '../../model/index.js';
import type { DashboardController } from '../../react/index.js';
import { createToastManager, ToastProvider } from '../components/toast.js';
import { useViewMessages } from '../MessagesProvider.js';
import { FilterBar } from './FilterBar.js';
import type { BoardFilterModes } from './filterModes.js';
import { AddFilterMenu, FilterSettings } from './FilterSettings.js';
import {
  BoardToasts,
  FilterWiringContext,
  WiringBar,
  wireAndSay,
  type FilterWiring,
} from './FilterWiring.js';

/** What the board draws of its filters, and where. */
export interface BoardFilterParts {
  /** The filter bar, over everything else on the board (D22 F). */
  bar: ReactNode;
  /** 「筛选 ＋」, for the edit bar while the board is built (D22 G). */
  add: ReactNode;
  /** The line saying which filter is being wired, while one is. */
  wiring: ReactNode;
  /** Around the board: the wiring its panels read, and its toasts. */
  wrap(children: ReactNode): ReactNode;
}

/**
 * A board's filters as the board draws them (D22 F, G): the bar; while the
 * board is built, 「筛选 ＋」, each filter's settings, and wiring — every
 * panel's strip, and after a pick on one panel a toast saying how many more
 * auto-connect wired, with 撤销. Wiring ends with the building.
 */
export function useBoardFilters({
  dashboard,
  editing,
  say,
  modes,
}: {
  dashboard: DashboardController;
  editing: boolean;
  /** What the board's live region says. */
  say(words: string): void;
  /** How an embedding page offers each filter (`FilterBar.modes`). */
  modes?: BoardFilterModes | undefined;
}): BoardFilterParts {
  const messages = useViewMessages();
  const [settingsOf, setSettingsOf] = useState<string | null>(null);
  const [wiringOf, setWiringOf] = useState<string | null>(null);
  const [toasts] = useState(() => createToastManager());
  const edit = dashboard.edit;
  const building = editing && edit !== null;
  const filter = building
    ? (dashboard.filterFields.find(field => field.name === wiringOf) ?? null)
    : null;

  const wiring: FilterWiring | null = filter && {
    filter,
    wire: (panelId, field) =>
      wireAndSay(dashboard, filter, panelId, field, (connected, wiredBy) => {
        const panel = dashboard.panels.find(entry => entry.id === panelId);
        const label =
          panel?.runtime?.definition.fields.find(
            entry => entry.name === wiredBy,
          )?.label ?? wiredBy;
        toasts.add({
          title: messages.label(
            connected.length === 1
              ? 'label.filters.auto-wired-one'
              : 'label.filters.auto-wired',
            { count: connected.length, field: label },
          ),
          timeout: 10_000,
          actionProps: {
            children: messages.label('label.filters.undo'),
            onClick: () => edit?.unbindPanels(filter.name, connected),
          },
        });
      }),
  };

  const settings = (field: DashboardField) =>
    building ? (
      <FilterSettings
        field={field}
        dashboard={dashboard}
        open={settingsOf === field.name}
        onOpenChange={open => setSettingsOf(open ? field.name : null)}
        onWire={() => setWiringOf(field.name)}
        onRemoved={label => {
          setSettingsOf(null);
          if (wiringOf === field.name) setWiringOf(null);
          say(messages.label('label.filters.removed', { filter: label }));
        }}
      />
    ) : null;

  return {
    bar: (
      <FilterBar
        dashboard={dashboard}
        settings={building ? settings : undefined}
        onRemoveGrouping={
          building ? () => edit.setTimeGrouping(null) : undefined
        }
        modes={modes}
      />
    ),
    add: building && (
      <AddFilterMenu dashboard={dashboard} onAdded={setSettingsOf} />
    ),
    wiring: filter && (
      <WiringBar filter={filter} onDone={() => setWiringOf(null)} />
    ),
    wrap: children => (
      <ToastProvider toastManager={toasts}>
        <FilterWiringContext.Provider value={wiring}>
          {children}
        </FilterWiringContext.Provider>
        <BoardToasts />
      </ToastProvider>
    ),
  };
}
