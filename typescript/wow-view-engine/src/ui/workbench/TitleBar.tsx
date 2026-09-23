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

import type { ReactNode, RefObject } from 'react';
import type { ViewKind } from '../../model/index.js';
import type { WorkbenchController } from '../../react/index.js';
import { EditorBandToggle } from '../EditorBand.js';
import { RefreshControl } from '../RefreshControl.js';
import { RenderBoundary } from '../RenderBoundary.js';
import { ViewExpandToggle, type ViewExpansion } from '../ViewExpansion.js';
import { ViewHeader, type ViewHeaderState } from '../ViewHeader.js';
import type { WorkbenchShellProps } from '../WorkbenchShell.js';

export interface TitleBarProps extends Pick<
  WorkbenchShellProps,
  | 'actions'
  | 'freshness'
  | 'editorModes'
  | 'onRenderFailure'
  | 'build'
  | 'commitElsewhere'
> {
  /** The commands, the refresh and the write callbacks the bar is drawn from. */
  workbench: WorkbenchController;
  state: ViewHeaderState;
  kind: ViewKind;
  titleId: string;
  titleRef: RefObject<HTMLHeadingElement | null>;
  /** What resets the actions' boundary: the open view. */
  resetKeys: readonly unknown[];
  /** Whether the bar names the view itself — not while `leading` does. */
  namesView: boolean;
  /** What the folded sidebar leaves at the head of the identity group. */
  leading?: ReactNode;
  /** The editor fold's toggle name; given only when the editor folds. */
  editorLabel?: string;
  editorPending: number;
  /** Whether the view's own query is running, for the default refresh. */
  busy: boolean;
  /** Whether the workbench offers to fill the screen at all. */
  expandable: boolean;
  fill: ViewExpansion;
  expandViewRef: RefObject<HTMLButtonElement | null>;
  /** A save-as created a view: the shell sends focus to its title. */
  onCreated(): void;
}

/**
 * The title bar's block: a banner, ruled off rather than boxed — a card
 * around the thing that names the page is a card around the page. The
 * identity group on the left is `ViewHeader`'s; the right-hand group is the
 * view-level controls this block assembles.
 */
export function TitleBar({
  workbench,
  state,
  kind,
  titleId,
  titleRef,
  actions,
  resetKeys,
  onRenderFailure,
  namesView,
  leading,
  editorLabel,
  editorModes,
  editorPending,
  freshness,
  busy,
  expandable,
  fill,
  expandViewRef,
  onCreated,
  build,
  commitElsewhere,
}: TitleBarProps) {
  return (
    <div
      data-slot="view-header-block"
      // The rule runs the whole width of the column, under `main`'s own
      // padding, so it meets the sidebar's edge and the two heads end
      // on one continuous line rather than two dashes with a gap.
      className="border-border -mx-4 border-b px-4 pb-3"
    >
      <ViewHeader
        state={state}
        kind={kind}
        commands={workbench.commands}
        titleId={titleId}
        titleRef={titleRef}
        actions={
          actions != null && (
            <RenderBoundary
              name="actions"
              compact
              resetKeys={resetKeys}
              onFailure={onRenderFailure}
            >
              {actions}
            </RenderBoundary>
          )
        }
        // Something in `leading` already shows the kind and the name,
        // so the bar does not show them a second time.
        namesView={namesView}
        leading={leading}
        build={build}
        commitElsewhere={commitElsewhere}
        trailing={
          // All three are answers to *how am I looking at this*,
          // which is what this group is, and they read outwards: the
          // editor governs what the view asks, filling the screen
          // governs the room the answer gets, and the refresh
          // governs how often it is renewed.
          <>
            {editorLabel !== undefined && (
              <EditorBandToggle
                label={editorLabel}
                modes={editorModes}
                pending={editorPending}
              />
            )}
            {freshness ?? (
              <RefreshControl
                refresh={workbench.refresh}
                variant="outline"
                busy={busy}
              />
            )}
            {expandable && (
              <ViewExpandToggle expansion={fill} ref={expandViewRef} />
            )}
          </>
        }
        onSaved={workbench.onSaved}
        onCreated={onCreated}
        onRenamed={workbench.onRenamed}
        onDeleted={workbench.onDeleted}
        onRecovered={workbench.onRecovered}
      />
    </div>
  );
}
