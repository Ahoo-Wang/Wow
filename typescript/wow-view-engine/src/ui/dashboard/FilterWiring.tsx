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

import { createContext, useContext, useId } from 'react';
import { cn } from 'cn';
import { CableIcon, XIcon } from 'lucide-react';
import { wireableFields } from '../../dashboard/index.js';
import type { DashboardField } from '../../model/index.js';
import type {
  DashboardController,
  DashboardPanelView,
} from '../../react/index.js';
import { CompactSelect } from '../analysis/CompactSelect.js';
import { Badge } from '../components/badge.js';
import { Button } from '../components/button.js';
import {
  Toast,
  ToastAction,
  ToastClose,
  ToastContent,
  ToastTitle,
  ToastViewport,
  useToastManager,
} from '../components/toast.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The filter being wired (D22 G), and what a panel's strip does to it: wire
 * the panel through a field — by hand, auto-connecting the rest — or take
 * its wire off (`null`).
 */
export interface FilterWiring {
  filter: DashboardField;
  wire(panelId: string, field: string | null): void;
}

export const FilterWiringContext = createContext<FilterWiring | null>(null);

/** The filter being wired, where the board is in wiring; `null` otherwise. */
export function useFilterWiring(): FilterWiring | null {
  return useContext(FilterWiringContext);
}

/** What 「不接」 is on a strip's select, which no field is called. */
const UNWIRED = '\u0000';

/**
 * One panel's strip while a filter is wired (D22 G): 「筛选字段：〈字段〉 ▾」
 * listing the panel's fields of the filter's type alone, 「没有可接的字段」
 * when it has none, and 「手动」 on a wire chosen by hand rather than made
 * by auto-connect.
 */
export function PanelWiring({
  panel,
  name,
  wiring,
}: {
  panel: DashboardPanelView;
  /** What the panel is called on screen. */
  name: string;
  wiring: FilterWiring;
}) {
  const messages = useViewMessages();
  const definition = panel.runtime?.definition;
  if (panel.panel.kind !== 'view' || !definition) return null;
  const { filter } = wiring;
  const fields = wireableFields(filter, definition.fields);
  const reach = panel.reach[filter.name];
  const wired = reach?.wired ? reach : null;
  return (
    <div
      data-slot="panel-wiring"
      data-wired={wired ? true : undefined}
      className={cn(
        'bg-muted/60 flex flex-wrap items-center gap-1.5 rounded-md border px-2 py-1',
        TEXT_UI,
      )}
    >
      <CableIcon aria-hidden className="text-muted-foreground size-3.5" />
      {fields.length === 0 ? (
        <span data-slot="panel-wiring-none" className="text-muted-foreground">
          {messages.label('label.filters.no-field')}
        </span>
      ) : (
        <>
          <span aria-hidden="true" className="text-muted-foreground">
            {messages.label('label.filters.wire-field')}
          </span>
          <CompactSelect
            label={messages.label('label.filters.wire-field-of', {
              panel: name,
              filter: filter.label,
            })}
            items={[
              {
                value: UNWIRED,
                label: messages.label('label.filters.unwired'),
              },
              ...fields.map(field => ({
                value: field.name,
                label: field.label,
              })),
            ]}
            value={wired ? wired.field : UNWIRED}
            onChange={next =>
              wiring.wire(panel.id, next === UNWIRED ? null : next)
            }
          />
          {wired && !wired.auto && (
            <Badge
              data-slot="panel-wiring-manual"
              variant="outline"
              title={messages.label('label.filters.manual-hint')}
            >
              {messages.label('label.filters.manual')}
            </Badge>
          )}
        </>
      )}
    </div>
  );
}

/**
 * The line over the board while a filter is wired: which filter, what to
 * do, and the way out.
 */
export function WiringBar({
  filter,
  onDone,
}: {
  filter: DashboardField;
  onDone(): void;
}) {
  const messages = useViewMessages();
  const titleId = useId();
  return (
    <div
      data-slot="dashboard-wiring-bar"
      role="region"
      aria-labelledby={titleId}
      className="bg-muted/50 flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2"
    >
      <p id={titleId} className="flex items-center gap-1.5 text-sm font-medium">
        <CableIcon aria-hidden className="size-4" />
        {messages.label('label.filters.wiring', { filter: filter.label })}
      </p>
      <p className={cn('text-muted-foreground min-w-0 grow', TEXT_UI)}>
        {messages.label('label.filters.wiring-hint')}
      </p>
      <Button
        data-slot="dashboard-wiring-done"
        size="sm"
        className="ml-auto"
        onClick={onDone}
      >
        {messages.label('label.filters.wiring-done')}
      </Button>
    </div>
  );
}

/**
 * The board's toasts — 「已自动接上 N 个」 with 撤销 — in the package's own
 * root rather than portalled to the body, where the theme would not reach
 * them (`popups.tsx`). Inside a `ToastProvider` the board holds.
 */
export function BoardToasts() {
  const messages = useViewMessages();
  const { toasts } = useToastManager();
  return (
    <ToastViewport data-slot="dashboard-toasts">
      {toasts.map(item => (
        <Toast key={item.id} toast={item}>
          <ToastContent>
            <ToastTitle className="min-w-0 flex-1" />
            <ToastAction />
            <ToastClose aria-label={messages.label('label.filters.dismiss')}>
              <XIcon aria-hidden="true" />
            </ToastClose>
          </ToastContent>
        </Toast>
      ))}
    </ToastViewport>
  );
}

/**
 * What wiring one panel does on the board, said as a toast with its undo:
 * the panels auto-connect wired with it (`bindPanel`), or nothing to say.
 */
export function wireAndSay(
  dashboard: DashboardController,
  filter: DashboardField,
  panelId: string,
  field: string | null,
  say: (connected: readonly string[], field: string) => void,
): void {
  const edit = dashboard.edit;
  if (!edit) return;
  if (field === null) {
    edit.unbindPanels(filter.name, [panelId]);
    return;
  }
  const connected = edit.bindPanel(filter.name, panelId, field);
  if (connected.length > 0) say(connected, field);
}
