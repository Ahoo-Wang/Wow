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

import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react';
import { PencilIcon } from 'lucide-react';
import type { DashboardFilters } from '../../model/index.js';
import {
  useLeaveGuard,
  type DashboardController,
  type LeaveGuardState,
} from '../../react/index.js';
import { Button } from '../components/button.js';
import type { MessageFormatters } from '../MessagesProvider.js';

export interface BuildShellOptions {
  dashboard: DashboardController;
  messages: MessageFormatters;
  /** Whether 「编辑」 is offered: a board is open and this reader may save it. */
  canEdit: boolean;
  /** Whether the board is being built on this surface right now. */
  editing: boolean;
  /**
   * What the leave guard reads while the board is built, on a surface whose
   * own frame guards nothing — an embed. A workbench passes `null`: its
   * shell's guard (`useWorkbench`) already asks about the whole draft,
   * building or not.
   */
  guard: LeaveGuardState | null;
  /** The tab on screen as the host is told it; `undefined` while no board is open. */
  tab: string | null | undefined;
  onTabChange?(tabId: string | null): void;
  /**
   * What the filters hold as the host is told it — the surface decides
   * whose values those are; `undefined` while no board is open.
   */
  filters: DashboardFilters | undefined;
  onFiltersChange?(filters: DashboardFilters): void;
}

export interface BuildShell {
  /**
   * 「编辑」, the primary button, for the surface to draw last among its
   * controls; nothing when not offered or while building.
   */
  editButton: ReactNode;
}

/**
 * The part of building a board every surface that shows one shares
 * (A-14/Q-06): the workbench's title bar and an embed's first row alike.
 *
 * - 「编辑」, only for whoever may save the board and only while it is read;
 * - the keyboard that pressed 保存 or 取消 goes back to it as it comes back
 *   — only as the building ends, and only when the focus was lost with the
 *   edit bar: an opening view never takes it;
 * - the leave guard over a draft being built, where the surface's frame has
 *   none of its own;
 * - the tab on screen and what the filters hold, told to the host as they
 *   change — the board opening included — for its address. The package never
 *   touches the address itself.
 */
export function useBuildShell({
  dashboard,
  messages,
  canEdit,
  editing,
  guard,
  tab,
  onTabChange,
  filters,
  onFiltersChange,
}: BuildShellOptions): BuildShell {
  useEffect(() => {
    if (tab !== undefined) onTabChange?.(tab);
  }, [tab, onTabChange]);
  useEffect(() => {
    if (filters !== undefined) onFiltersChange?.(filters);
  }, [filters, onFiltersChange]);

  // 「编辑」 leaves as the building starts and comes back as it ends.
  const editButton = useRef<HTMLButtonElement>(null);
  const wasEditing = useRef(editing);
  useLayoutEffect(() => {
    const ended = wasEditing.current && !editing;
    wasEditing.current = editing;
    if (!ended) return;
    const active = document.activeElement;
    if (active === null || active === document.body)
      editButton.current?.focus();
  }, [editing]);

  // A board being built holds a draft nothing else keeps: closing the tab on
  // it is asked about. The host's own navigation does not come through here.
  useLeaveGuard(editing ? guard : null);

  const { setBuilding } = dashboard;
  return {
    // The one primary of a board being read (D32): nothing on it runs a
    // query by hand — the filters run as they change — so the thing to do
    // next is to build it, and the surface draws it last on its line, where
    // the edit bar's 「保存」 stands once it is pressed.
    editButton: canEdit && !editing && (
      <Button
        ref={editButton}
        data-slot="dashboard-edit"
        data-emphasis="primary"
        size="sm"
        onClick={() => setBuilding(true)}
      >
        <PencilIcon data-icon="inline-start" />
        {messages.label('label.dashboard.edit')}
      </Button>
    ),
  };
}
