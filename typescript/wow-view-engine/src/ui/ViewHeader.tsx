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

import { useId, type ReactNode, type RefObject } from 'react';
import {
  audienceOf,
  isSystemScope,
  type ViewConfig,
  type ViewInstance,
  type ViewKind,
} from '../model/index.js';
import { cn } from 'cn';
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
  /**
   * Called only when a save-as actually created a view — not when a save
   * landed in place. The two read alike from `onSaved`, and they end
   * differently: an in-place save leaves the user on the button they
   * pressed, while a copy closes its dialog, opens another view and has
   * nowhere to put focus but `<body>`. This is how a host learns which one
   * happened, and `WorkbenchShell` answers it by sending focus to the new
   * view's title.
   */
  onCreated?(instance: ViewInstance): void;
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
  /**
   * The view-level controls of the right-hand group — the editor's fold,
   * and in time whatever else governs how this view is being looked at.
   * They come before the host's own actions, which always end the line.
   */
  trailing?: ReactNode;
  /**
   * Whether this bar is the thing that says which view is open. False when
   * something in `leading` already does — a view switcher shows the kind and
   * the title both — and the bar then drops its own icon and leaves the
   * title as a heading a screen reader still reaches but nobody sees twice.
   */
  namesView?: boolean;
  /**
   * The id the view title carries, so the region the view is drawn in can
   * name itself by it. One is generated when the caller has nothing to point
   * at it.
   */
  titleId?: string;
  /**
   * Where the view title sits in the document outline. It is a heading
   * rather than a line of text because it names everything under it, and a
   * screen reader navigates by headings; the level is the host's, since only
   * the page around the workbench knows what it is nested in.
   */
  headingLevel?: HeadingLevel;
  /**
   * A handle on the title heading, for a host that has to put focus on the
   * view itself — after a copy is created and opened, for one. It is a ref
   * rather than a lookup by `titleId`, because the element belongs to this
   * component and a caller should not have to go into the document for it.
   */
  titleRef?: RefObject<HTMLHeadingElement | null>;
}

type HeadingLevel = 1 | 2 | 3 | 4 | 5 | 6;

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
  onCreated,
  onRenamed,
  onDeleted,
  onRecovered,
  actions,
  leading,
  trailing,
  namesView = true,
  titleId,
  headingLevel = 2,
  titleRef,
}: ViewHeaderProps) {
  const messages = useViewMessages();
  const generatedId = useId();
  if (!state) return null;

  // Preflight leaves a heading at the size and weight of the text around it,
  // so this is the outline the title was missing and not a change of look.
  const Title: `h${HeadingLevel}` = `h${headingLevel}`;
  const Kind = KIND_ICON[kind];
  const audience = audienceOf(state.scope);
  const Audience = AUDIENCE_ICON[audience];
  // A system view is a shared view; its tag says where it came from, which
  // the audience alone cannot.
  const scopeKey = isSystemScope(state.scope) ? 'system' : audience;

  return (
    // The bar is a query container named `header`, and it is the *only*
    // thing the parts inside it are allowed to ask about. What they need to
    // know is how much room this bar has, which the viewport does not
    // answer: a 360px panel on a 1440px page had `definition-title`'s
    // viewport `sm:inline` showing "Orders" while the view's own name was
    // squeezed to "全…". Everything that gives way below a width — the
    // definition's title in `WorkbenchShell`, the audience tag here, the
    // words on the save commands — reads `@…/header`.
    <div
      data-slot="view-header-band"
      className="@container/header flex flex-col gap-2"
    >
      <div
        data-slot="view-header"
        className="flex min-h-10 flex-wrap items-center gap-2"
      >
        {/* Which view this is, and the commands that keep it: one group, in
            the order a user reads it — where it sits, what it is, what to do
            with it.

            No `min-w-0` on it, and that is the whole of how this bar narrows
            honestly. `min-w-0` lets a flex item be squeezed below what its
            contents need, and every content here but the title is
            `shrink-0`: the group reported that it fitted at 96.8px while its
            children needed 206.9px, so the row never wrapped and the save
            commands were painted 102px *over* the controls on the right —
            at a phone's width, `elementFromPoint` answered the filter toggle
            at Save's left edge, middle and right edge alike. Sized to its
            contents instead, the group tells the truth about what it needs,
            and `flex-wrap` above puts the controls on a second line at the
            width where they no longer both fit. */}
        <div
          data-slot="view-identity"
          // `grow`, not `flex-1`: a basis of 0% tells the row this group needs
          // nothing, so it never wraps and the group is squeezed to its
          // min-content with Save spilling past the bar. With an `auto` basis
          // the row wraps the controls at the width where both no longer fit.
          className="flex grow items-center gap-2"
        >
          {leading}
          {namesView && (
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
          )}

          {/* Always a heading, because the region around it is named by its
              id — an id that addresses nothing is a broken label. When
              something else on the line already shows the title, the heading
              stays for a screen reader and goes out of the layout.

              It is the group's one spring: `w-0 grow` starts it at nothing
              and hands it whatever the row has left over, so the title is
              what gives when the bar narrows and the name is the only thing
              that truncates. `truncate` alone would not do it — a nowrap
              heading still *asks* for its full text, and the group sized to
              its contents would then grow with the title rather than clip
              it, which is the same overflow by another route. A width of
              zero is a definite size the group can count on, which is why
              `max-w-fit` was no answer.

              And a spring with no floor is a name that gives until there is
              nothing left of it: at 375px the switcher's label was 40px —
              "待出…" — while the tag beside it held 74px and the save
              commands 93px, so the one thing the bar exists to say was the
              one thing not on it. `min-w-[6em]` is that floor. It is a
              floor on the *group* as much as on the name: `min-width` is
              what a flex item reports upwards, so an identity group that
              cannot spare 6em now says so, and `flex-wrap` above puts the
              controls on their own line instead. */}
          <Title
            ref={titleRef}
            id={titleId ?? generatedId}
            // Focusable when focus is *sent* here and never a tab stop: a
            // view that has just been created is what the user is now
            // looking at, and its name is where a screen reader should
            // resume. Tab order is untouched.
            tabIndex={-1}
            data-slot="view-title"
            data-dirty={state.dirty || undefined}
            // `truncate` is visual only — a screen reader still reads the
            // whole name, but a pointer user has no way back to it once the
            // ellipsis lands. `ConditionPill` sets this precedent on the
            // field name it truncates the same way.
            title={state.title}
            className={cn(
              'truncate font-medium',
              // `sr-only` sets a width of its own, so the spring is only for
              // the title that is actually on the line.
              namesView ? 'w-0 min-w-[6em] grow' : 'sr-only',
            )}
          >
            {state.title}
          </Title>

          {/* Who the view is for, said as a word after the name rather than
              as a badge beside it (D12): the name is the thing on this line,
              and a pill fought it for the eye. Below `@md` the word goes —
              it stays in the heading's neighbourhood for a screen reader —
              rather than truncating, which for a two-character audience is
              the same as lying. */}
          <span
            data-slot="view-audience"
            data-scope={scopeKey}
            className="text-muted-foreground @max-md/header:sr-only shrink-0 text-xs"
          >
            <Audience aria-hidden className="mr-1 inline size-3 align-[-1px]" />
            {messages.label(`label.scope.tag.${scopeKey}`)}
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

          <SaveActions
            commands={commands}
            title={state.title}
            onSaved={onSaved}
            onCreated={onCreated}
            onRenamed={onRenamed}
            onDeleted={onDeleted}
            onRecovered={onRecovered}
          />
        </div>

        {/* How it is being looked at. The host's own actions end the line:
            everything before them is this package's, and a page that adds a
            button does not have to know what it is standing next to.

            `ml-auto` is for the line this group gets to itself: on one line
            the identity group's `flex-1` already pushes it to the end, but
            once the bar wraps this group starts a line of its own and
            without it lands hard left — under the kind icon, reading as a
            second row of the identity group rather than as the other half
            of the bar. */}
        <div
          data-slot="view-controls"
          className="ml-auto flex shrink-0 items-center gap-2"
        >
          {trailing}
          {trailing && actions && (
            <Separator orientation="vertical" className="h-4" />
          )}
          {actions}
        </div>
      </div>

      <WriteOutcome
        commands={commands}
        title={state.title}
        onSaved={onSaved}
        onCreated={onCreated}
        onRenamed={onRenamed}
        onDeleted={onDeleted}
        onRecovered={onRecovered}
      />
    </div>
  );
}
