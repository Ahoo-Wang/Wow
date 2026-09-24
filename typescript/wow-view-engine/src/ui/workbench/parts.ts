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

import type { ReactNode } from 'react';
import type { WorkbenchShellProps } from '../WorkbenchShell.js';

/**
 * What one kind of view puts into the shell's slots: its editor and how it
 * folds, its toolbar, its result and the way out of an error, and the host's
 * actions over it.
 *
 * It is a `Pick` of the shell's own props rather than a contract of its own
 * (D18-1): the shell has one slot table, and a kind fills the part of it that
 * is about the view rather than about the frame — the sidebar, the title, the
 * theme and the wording stay the workbench's to pass. Everything the shell
 * can draw by itself from the controller — the refresh, the query strip, the
 * warnings — is not in it: a part that repeated those would be the drift
 * this type exists to end.
 */
export type WorkbenchParts = Pick<
  WorkbenchShellProps,
  | 'actions'
  | 'search'
  | 'editor'
  | 'editorLabel'
  | 'editorModes'
  | 'editorOpen'
  | 'onEditorOpenChange'
  | 'editorPending'
  | 'toolbar'
  | 'result'
  | 'errorAction'
  | 'besideResult'
  | 'nameIssue'
  | 'resultSlots'
  | 'panel'
  | 'onPanelClose'
>;

/**
 * A kind's parts are made by a component, not a function: each kind's
 * controller is a hook (`useRecordTable`, `useAnalysisEditor`), and a hook
 * cannot be called on a branch. So the parts are handed back through a
 * render prop, and the workbench draws the shell around them.
 *
 * The component stays mounted whether or not a view of its kind is open —
 * it is handed `null` then, and hands back no parts. The shell is drawn
 * inside the render prop, so a part that came and went with the view would
 * take the shell with it: every switch releases one runtime before the next
 * opens, and a shell remounted in between forgets the screen's posture —
 * the list folded away, the view filling the screen.
 */
export type RenderParts = (parts: WorkbenchParts) => ReactNode;

/** What a part hands back while no view of its kind is open. */
export const NO_PARTS: WorkbenchParts = {};
