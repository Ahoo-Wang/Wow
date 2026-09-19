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

import { useId, type ReactNode } from 'react';
import { cn } from 'cn';
import type { Issue, ViewKind } from '../model/index.js';
import type { WorkbenchController } from '../react/index.js';
import { Alert, AlertDescription, AlertTitle } from './components/alert.js';
import { Separator } from './components/separator.js';
import { Skeleton } from './components/skeleton.js';
import { AppliedBar } from './AppliedBar.js';
import { LeaveDialog } from './LeaveGuard.js';
import { ErrorStrip, WarningStrip } from './StatusStrip.js';
import { useViewMessages } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { ViewHeader } from './ViewHeader.js';
import { ViewList } from './ViewList.js';
import { ViewSurface } from './ViewSurface.js';

export interface WorkbenchShellProps {
  workbench: WorkbenchController;
  /** The kind being drawn; the title bar and the error strip name it. */
  kind: ViewKind;
  /** The definition's title, which names the sidebar. */
  title?: string;
  theme?: 'light' | 'dark';
  /** Wording, merged over what is already in force: where a host translates. */
  messages?: ViewMessages;
  /**
   * The language dates and times show in; the runtime's when left out. It is
   * the same choice as `messages`, made for values rather than words.
   */
  locale?: string;
  /**
   * The zone times show in — the engine's, so a row's time reads on the same
   * clock a relative condition was filtered by.
   */
  timeZone?: string;
  /** The host's own global actions, rendered left of the save commands. */
  actions?: ReactNode;
  /** The view's own editor, between the title bar and the strips. */
  editor?: ReactNode;
  /** Strips only one kind has, under the two every kind shows. */
  strips?: ReactNode;
  /** The rows, the chart, the panels — what the page is for. */
  result?: ReactNode;
  /**
   * One boolean governs the sidebar, so collapsing it is a change in one
   * place rather than in the layout of every part beside it.
   */
  sidebarOpen?: boolean;
  /**
   * Whether the result below was ever asked for; the applied bar renders
   * nothing until it was. The open view's own result when left out — a
   * dashboard has none of its own and answers from its panels instead.
   */
  hasResult?: boolean;
  /** The warnings to show; every one the view reports when left out. */
  warnings?: readonly Issue[];
  /** Extra classes for the main column. */
  className?: string;
}

/**
 * The frame the three default workbenches share: the view list beside it, the
 * title bar over it, the findings under that, and the two slots that make one
 * kind different from another.
 *
 * The result is the point of it, so the page is ordered by how close each
 * part stands to it: which view this is, the editor folded out of the way,
 * anything the view has to say in a line, what the result was fetched under,
 * then the result itself. Everything the shell decides it decides from one
 * `WorkbenchController`; it holds no state of its own.
 *
 * It is one composition of the controllers in `/react`, not a privileged one.
 * An application that wants different markup builds its own from
 * `useWorkbench` and loses nothing.
 */
export function WorkbenchShell({
  workbench,
  kind,
  title,
  theme,
  messages: wording,
  locale,
  timeZone,
  actions,
  editor,
  strips,
  result,
  sidebarOpen = true,
  hasResult,
  warnings,
  className,
}: WorkbenchShellProps) {
  const messages = useViewMessages(wording);
  const titleId = useId();
  const { filter, leave, list, manager, opened, state, unopenable } = workbench;
  // A view that opened and is this page's to draw. Anything else is reported
  // instead of being dressed up as a title bar over an empty body.
  const open = state !== null && workbench.runtime !== null && !unopenable;

  return (
    <ViewSurface
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={timeZone}
      className="gap-0 md:flex-row"
    >
      {sidebarOpen && (
        <>
          <aside
            data-slot="view-sidebar"
            className="flex w-56 shrink-0 flex-col gap-2 p-3"
          >
            <ViewList
              list={list}
              title={title}
              currentId={state?.saved?.id ?? null}
              onOpen={workbench.choose}
              // Only when something on the list can actually be managed: a
              // reader with no write permission at all would otherwise get a
              // button whose only lesson is that it leads to a dialog of
              // read-only rows.
              manager={manager.can.anything ? manager : undefined}
              openDirtyId={state?.dirty ? (state.saved?.id ?? null) : null}
            />
          </aside>

          <Separator orientation="vertical" className="hidden md:block" />
        </>
      )}

      <main
        // Only while the title is on screen: an id that addresses nothing is
        // a broken label rather than a missing one.
        aria-labelledby={open ? titleId : undefined}
        className={cn('flex min-w-0 flex-1 flex-col gap-3 p-3', className)}
      >
        {unopenable && (
          <Alert variant="destructive">
            <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
            <AlertDescription>{messages.issue(unopenable)}</AlertDescription>
          </Alert>
        )}

        {opened.loading && <Skeleton className="h-8 w-full" />}

        {open && (
          <>
            <ViewHeader
              state={state}
              kind={kind}
              commands={workbench.commands}
              titleId={titleId}
              actions={actions}
              onSaved={workbench.onSaved}
              onRenamed={workbench.onRenamed}
              onDeleted={workbench.onDeleted}
              onRecovered={workbench.onRecovered}
            />

            {editor}

            {/* What the condition editor marks on a pill is left to it; what
                it cannot mark is only ever read here. */}
            <ErrorStrip
              issues={filter.unmarked}
              title={
                kind === 'dashboard'
                  ? messages.label('label.dashboard.needs-fixing')
                  : undefined
              }
            />
            {/* Warnings block nothing — the result below is the real one — so
                they sit under the errors and never replace it. */}
            <WarningStrip issues={warnings ?? state.issues} />
            {strips}

            <AppliedBar
              filter={filter}
              hasResult={hasResult ?? state.result != null}
            />

            {result}
          </>
        )}
      </main>
      {/* Outside the surface, and so handed the wording directly: it is the
          one dialog an override would otherwise never reach. */}
      <LeaveDialog leave={leave} messages={wording} />
    </ViewSurface>
  );
}
