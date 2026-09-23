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

import { cn } from 'cn';
import type { ViewKind } from '../../model/index.js';
import type { WorkbenchController } from '../../react/index.js';
import { resultIssues } from '../../runtime/index.js';
import { SPACE } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ErrorStrip, NoteStrip, WarningStrip } from '../StatusStrip.js';
import type { WorkbenchShellProps } from '../WorkbenchShell.js';

export interface StatusLineProps extends Pick<
  WorkbenchShellProps,
  'warnings' | 'errorAction'
> {
  /** The filter's unmarked findings, the definition's and the list's. */
  workbench: WorkbenchController;
  /** The open view, whose findings and result are the rest of it. */
  state: NonNullable<WorkbenchController['state']>;
  /** The kind on screen, which names the error strip's title. */
  kind: ViewKind | undefined;
}

/**
 * The status line (D12 Ⅰ′): what the view reports about itself, under the
 * title bar and only when there is something to say — a config that will
 * not run, a warning that does not block. The last write's outcome is the
 * title bar's own line above. `empty:hidden` keeps the row out of the flow
 * when every strip rendered nothing, so the ruler's 16px does not stack
 * twice.
 */
export function StatusLine({
  workbench,
  state,
  kind,
  warnings,
  errorAction,
}: StatusLineProps) {
  const messages = useViewMessages();
  const { filter, list } = workbench;
  return (
    <div
      data-slot="status-line"
      className={cn('flex flex-col empty:hidden', SPACE.ROWS)}
    >
      <ErrorStrip
        // The definition's own findings beside the view's: an
        // error in the definition was reported to `onIssue` and to
        // nobody on screen (F-05).
        issues={[...filter.unmarked, ...workbench.definitionIssues]}
        title={
          kind === 'dashboard'
            ? messages.label('label.dashboard.needs-fixing')
            : undefined
        }
        action={errorAction}
      />
      {/* Warnings block nothing — the result below is the real one —
        so they sit under the errors and never replace it. Failed
        preferences are one: the list still works, in the server's
        order, so it is said as a warning and said once. */}
      <WarningStrip
        issues={[
          ...(warnings ?? [
            ...state.issues,
            ...resultIssues(state.result?.data),
          ]),
          ...workbench.definitionIssues,
          ...(list.preferencesError
            ? [
                {
                  ...list.preferencesError,
                  severity: 'warning' as const,
                },
              ]
            : []),
        ]}
      />
      {/* What is true of the answer and nothing is wrong with, under the
          warnings and quieter than them (`note`). */}
      <NoteStrip
        issues={[...state.issues, ...resultIssues(state.result?.data)]}
      />
    </div>
  );
}
