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

import {
  useCallback,
  useId,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react';
import {
  audienceOf,
  isSystemScope,
  type ViewConfig,
  type ViewKind,
} from '../model/index.js';
import { cn } from 'cn';
import {
  presentationOnlyEdits,
  type ViewRuntimeState,
} from '../runtime/index.js';
import type { SaveCommands } from '../react/index.js';
import { Badge } from './components/badge.js';
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from './components/alert-dialog.js';
import { DestructiveAction, SectionDivider } from './variants.js';
import { AUDIENCE_ICON, KIND_ICON, SYSTEM_ICON } from './kinds.js';
import { useViewMessages } from './MessagesProvider.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { AlertDialogContent, TooltipContent } from './popups.js';
import { SaveActions, UnsavedMark } from './SaveActions.js';
import { WriteOutcome, type ViewWriteCallbacks } from './WriteOutcome.js';
import { TEXT_UI } from './layout.js';

/**
 * What a title bar reads off the open view. It names the four facts rather
 * than the whole snapshot, so one header serves every kind of view: nothing
 * here is record-shaped, analysis-shaped or dashboard-shaped.
 */
export type ViewHeaderState = Pick<
  ViewRuntimeState<ViewConfig>,
  'title' | 'scope' | 'saved' | 'dirty' | 'draft'
>;

/**
 * The bar passes the write callbacks through rather than declaring its own:
 * they are {@link ViewWriteCallbacks}, and the components underneath here —
 * the button group and the outcome line — are what call them.
 */
export interface ViewHeaderProps extends ViewWriteCallbacks {
  /** Null while no view is open; the bar then renders nothing. */
  state: ViewHeaderState | null;
  kind: ViewKind;
  commands: SaveCommands;
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
   * The way into building the view, beside its save commands — a
   * dashboard's 「编辑」 (D22 A). Absent where the view is not built here.
   */
  build?: ReactNode;
  /**
   * Whether the view is committed and rolled back somewhere other than this
   * bar — a dashboard being built, whose edit bar holds 「完成」 and
   * 「取消」 (D22 A). The bar then leaves off its save commands and the
   * "edited" mark with its ↺: one way to do one thing, and a second Save or
   * a second revert beside the edit bar's would be the same command under
   * another name.
   */
  commitElsewhere?: boolean;
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
  build,
  commitElsewhere = false,
  namesView = true,
  titleId,
  headingLevel = 2,
  titleRef,
}: ViewHeaderProps) {
  const messages = useViewMessages();
  const generatedId = useId();
  // Whether the question in front of ↺ is on screen. It is this bar's and
  // not the mark's: `UnsavedMark` draws the command, and what a command
  // costs is decided where the view is, next to the leave guard that asks
  // the same thing about the same draft.
  const [asking, setAsking] = useState(false);
  const lookOnly =
    state?.saved != null &&
    presentationOnlyEdits(state.draft, state.saved.config);
  // Where focus lands once the draft is gone: ↺ is inside the "edited" mark,
  // and reverting takes the mark off the bar, so the button the dialog would
  // return to no longer exists and focus would fall to `<body>`. The view's
  // own name is what is left of where the user was.
  const heading = useRef<HTMLHeadingElement | null>(null);
  const holdTitle = useCallback(
    (node: HTMLHeadingElement | null) => {
      heading.current = node;
      if (titleRef) titleRef.current = node;
    },
    [titleRef],
  );
  if (!state) return null;

  // Preflight leaves a heading at the size and weight of the text around it,
  // so this is the outline the title was missing and not a change of look.
  const Title: `h${HeadingLevel}` = `h${headingLevel}`;
  const Kind = KIND_ICON[kind];
  const audience = audienceOf(state.scope);
  // A system view is a shared view; its tag says where it came from, which
  // the audience alone cannot, and it wears the lock the list marks it with.
  const system = isSystemScope(state.scope);
  const scopeKey = system ? 'system' : audience;
  const Audience = system ? SYSTEM_ICON : AUDIENCE_ICON[audience];

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
          {/* `truncate` is visual only — a screen reader still reads the
              whole name, but a pointer user has no way back to it once the
              ellipsis lands, so the whole of it is one hover away. A
              `Tooltip` rather than the native `title` this used to be
              (D16): `title` opens for a mouse and for nothing else, while
              the heading is focusable and a tap opens this one. The trigger
              renders the heading itself, so the spring's arithmetic —
              `w-0 grow` against the group — is untouched: nothing is
              wrapped around it. */}
          <Tooltip>
            <TooltipTrigger
              render={
                <Title
                  ref={holdTitle}
                  id={titleId ?? generatedId}
                  // Focusable when focus is *sent* here and never a tab
                  // stop: a view that has just been created is what the user
                  // is now looking at, and its name is where a screen reader
                  // should resume. Tab order is untouched.
                  tabIndex={-1}
                  data-slot="view-title"
                  data-dirty={state.dirty || undefined}
                  className={cn(
                    'truncate font-medium',
                    // `sr-only` sets a width of its own, so the spring is
                    // only for the title that is actually on the line.
                    // `max-w-max` caps the spring at the name's own width,
                    // so the audience and Save stand against it rather than
                    // at the end of the row; `w-0` still keeps the group's
                    // wrap arithmetic.
                    namesView ? 'w-0 max-w-max min-w-[6em] grow' : 'sr-only',
                  )}
                />
              }
            >
              {state.title}
            </TooltipTrigger>
            <TooltipContent>{state.title}</TooltipContent>
          </Tooltip>

          {/* Who the view is for, said as a word after the name rather than
              as a badge beside it (D12): the name is the thing on this line,
              and a pill fought it for the eye. Below `@md` the word goes —
              it stays in the heading's neighbourhood for a screen reader —
              rather than truncating, which for a two-character audience is
              the same as lying. */}
          <span
            data-slot="view-audience"
            data-scope={scopeKey}
            className={cn(
              'text-muted-foreground @max-md/header:sr-only shrink-0',
              TEXT_UI,
            )}
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
            // With the way back on it: the mark says "edited", and the one
            // command that answers that stands right after the word.
            //
            // The mark is handed the commands with a question in front of
            // the one that throws the draft away. ↺ is not undoable — the
            // runtime has no way to put an arbitrary draft back, only the
            // saved one — and losing a whole draft to a single press of a
            // 24px icon is the same loss the leave guard already stops to
            // ask about. So it is asked, in the same words and the same
            // shape; a dialog would be friction over an action that could
            // be taken back, and this one cannot.
            state.dirty &&
            !commitElsewhere &&
            (lookOnly ? (
              // Only the layout or the chart changed (D23 Q15): the mark
              // says so, and ↺ puts the saved look back at once — what it
              // loses is a way of looking, nothing a question would guard.
              <UnsavedMark
                lookOnly
                commands={{
                  ...commands,
                  revert: () => {
                    commands.revert();
                    heading.current?.focus();
                  },
                }}
              />
            ) : (
              <UnsavedMark
                commands={{ ...commands, revert: () => setAsking(true) }}
              />
            ))
          )}

          {!commitElsewhere && (
            <SaveActions
              commands={commands}
              title={state.title}
              onSaved={onSaved}
              onCreated={onCreated}
            />
          )}
          {build}
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
          {/* The line between two authorships — this package's controls and
              the host's own actions — and the only divider here that has to
              be seen at all. It is `SectionDivider` rather than a `Separator`
              with a colour written on it (D16-8); what it measured before
              and why it is what it is now are recorded there. */}
          {trailing && actions && <SectionDivider />}
          {actions}
        </div>
      </div>

      <RevertDialog
        open={asking}
        onOpenChange={setAsking}
        onConfirm={commands.revert}
        landing={heading}
      />

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

export interface RevertDialogProps {
  open: boolean;
  onOpenChange(open: boolean): void;
  /** Puts the saved config back; only the confirming answer calls it. */
  onConfirm(): void;
  /**
   * Where focus goes once the edits are gone. The button that opened this is
   * inside the "edited" mark, and the mark is what reverting takes off the
   * bar — so on the way out there is nothing to give focus back to, and the
   * view's own heading is the nearest thing to where the user was standing.
   */
  landing: RefObject<HTMLElement | null>;
}

/**
 * The question in front of ↺.
 *
 * It is the leave guard's question about the same draft, so it is the leave
 * guard's shape: an `AlertDialog` (the edits are lost for good, so an
 * outside click must not be an answer), one sentence for what it costs, the
 * answer that stays first and the destructive one last, named after the
 * command it carries out rather than after the dialog.
 *
 * Asked rather than undone, because there is no undo to offer: a runtime can
 * put the *saved* config back and nothing else, so an "Undone · Undo" strip
 * would need a way to restore an arbitrary draft that does not exist. A
 * dialog in front of an action that could be taken back is friction; in
 * front of one that cannot, it is the only place the user gets to decide.
 */
export function RevertDialog({
  open,
  onOpenChange,
  onConfirm,
  landing,
}: RevertDialogProps) {
  const messages = useViewMessages();
  // Which answer closed it. Base UI returns focus to whatever opened the
  // dialog, which is right for "Keep editing" — ↺ is still there — and
  // impossible for the other answer, which unmounts it.
  const took = useRef(false);
  return (
    <AlertDialog
      open={open}
      onOpenChange={next => {
        if (next) took.current = false;
        onOpenChange(next);
      }}
    >
      <AlertDialogContent
        finalFocus={() => (took.current ? landing.current : true)}
      >
        <AlertDialogHeader>
          <AlertDialogTitle>
            {messages.label('label.revert.heading')}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {messages.label('label.revert.consequence')}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>
            {messages.label('label.revert.keep')}
          </AlertDialogCancel>
          <DestructiveAction
            // The registry's action button is a plain `Button` — only
            // Cancel is a `Close` — so the answer that goes through says so
            // itself.
            onClick={() => {
              took.current = true;
              onConfirm();
              onOpenChange(false);
            }}
          >
            {messages.label('label.save.revert')}
          </DestructiveAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
