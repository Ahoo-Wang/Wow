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
import {
  audienceOf,
  isSystemScope,
  type ViewConfig,
  type ViewInstance,
  type ViewKind,
} from '../model/index.js';
import type { ViewRuntimeState, WriteAction } from '../runtime/index.js';
import type { SaveCommands } from '../react/index.js';
import { Badge } from './components/badge.js';
import { Separator } from './components/separator.js';
import { AUDIENCE_ICON, KIND_ICON } from './kinds.js';
import { useViewMessages } from './MessagesProvider.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { TooltipContent } from './popups.js';
import { SaveActions } from './SaveActions.js';
import { WriteOutcome } from './WriteOutcome.js';

/**
 * What a title bar reads off the open view. It names the four facts rather
 * than the whole snapshot, so one header serves every kind of view: nothing
 * here is record-shaped, analysis-shaped or dashboard-shaped.
 */
export type ViewHeaderState = Pick<
  ViewRuntimeState<ViewConfig>,
  'title' | 'scope' | 'saved' | 'dirty'
>;

export interface ViewHeaderProps {
  /** Null while no view is open; the bar then renders nothing. */
  state: ViewHeaderState | null;
  kind: ViewKind;
  commands: SaveCommands;
  onSaved?(instance: ViewInstance): void;
  onRenamed?(instance: ViewInstance): void;
  onDeleted?(): void;
  onRecovered?(action: WriteAction): void;
  /** The host's own global actions, rendered left of the save commands. */
  actions?: ReactNode;
  /**
   * Anything the surface needs at the very start of the line, before the
   * kind icon: what a collapsed sidebar leaves behind, for instance. It is
   * empty by default, and the bar makes no room for it when it is.
   */
  leading?: ReactNode;
}

/**
 * The one line that says which view this is, and the one place it is saved
 * from.
 *
 * Three facts sit on the left and none repeats another: which kind of view it
 * is, who it is for, and what it is called. The fourth — whether what is on
 * screen has been saved — is a mark beside the title rather than a state the
 * save button has to be read to discover. What became of the last write goes
 * underneath, where it can be a line of text instead of a banner over the
 * result.
 */
export function ViewHeader({
  state,
  kind,
  commands,
  onSaved,
  onRenamed,
  onDeleted,
  onRecovered,
  actions,
  leading,
}: ViewHeaderProps) {
  const messages = useViewMessages();
  if (!state) return null;

  const Kind = KIND_ICON[kind];
  const audience = audienceOf(state.scope);
  const Audience = AUDIENCE_ICON[audience];
  // A system view is a shared view; its tag says where it came from, which
  // the audience alone cannot.
  const scopeKey = isSystemScope(state.scope) ? 'system' : audience;

  return (
    <div data-slot="view-header-band" className="flex flex-col gap-2">
      <div data-slot="view-header" className="flex min-h-10 items-center gap-2">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {leading}
          <Tooltip>
            <TooltipTrigger
              render={
                <Kind className="text-muted-foreground size-4 shrink-0" />
              }
            />
            <TooltipContent>
              {messages.label(`label.kind.${kind}`)}
            </TooltipContent>
          </Tooltip>

          <Badge variant="secondary" className="shrink-0">
            <Audience aria-hidden />
            {messages.label(`label.scope.tag.${scopeKey}`)}
          </Badge>

          <span
            data-slot="view-title"
            data-dirty={state.dirty || undefined}
            className="truncate font-medium"
          >
            {state.title}
          </span>

          {/* Two different things, so two different words: one view was never
              saved, the other has been saved and edited since. */}
          {state.saved === null ? (
            <Badge variant="outline" className="shrink-0">
              {messages.label('label.header.new-view')}
            </Badge>
          ) : (
            state.dirty && (
              <Badge variant="outline" className="shrink-0">
                {messages.label('label.header.unsaved')}
              </Badge>
            )
          )}
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {actions}
          {actions && <Separator orientation="vertical" className="h-4" />}
          <SaveActions
            commands={commands}
            title={state.title}
            onSaved={onSaved}
            onRenamed={onRenamed}
            onDeleted={onDeleted}
            onRecovered={onRecovered}
          />
        </div>
      </div>

      <WriteOutcome
        commands={commands}
        title={state.title}
        onSaved={onSaved}
        onRenamed={onRenamed}
        onDeleted={onDeleted}
        onRecovered={onRecovered}
      />
    </div>
  );
}
