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

import { useState, type KeyboardEvent, type RefObject } from 'react';
import { useSortable } from '@dnd-kit/react/sortable';
import { OptimisticSortingPlugin } from '@dnd-kit/dom/sortable';
import { cn } from 'cn';
import {
  CheckIcon,
  GripVerticalIcon,
  PencilIcon,
  StarIcon,
  TrashIcon,
  XIcon,
} from 'lucide-react';
import {
  isSystemScope,
  toSummary,
  type ViewInstanceSummary,
} from '../model/index.js';
import type { ViewInstance, ViewPreferences } from '../model/index.js';
import type { WriteState } from '../runtime/index.js';
import type { ViewListState, ViewManagerController } from '../react/index.js';
import { Badge } from './components/badge.js';
import { IconButton } from './IconButton.js';
import { ButtonGroup } from './components/button-group.js';
import { Input } from './components/input.js';
import { DeleteDialog } from './DeleteDialog.js';
import { KIND_ICON } from './kinds.js';
import { useViewMessages } from './MessagesProvider.js';
import { OutcomeActions } from './OutcomeActions.js';

/**
 * The width every row gives its actions, whether or not it has all of them.
 *
 * A column of icons looks like a column, and a user reads it as one: what
 * sits under "Rename" on the row above must be "Rename" here too. Rows do
 * not all carry the same actions — a system view has no rename and no delete
 * — so with the cluster sized to its contents and pushed right, a system
 * row's one button landed exactly where every other row's "Delete" was: an
 * icon lying about what it does.
 *
 * The fix is a slot as wide as the fullest row and contents left-aligned
 * inside it. Re-measured now that the order is dragged rather than clicked:
 * the two arrows and the group between them are gone, so the fullest row is
 * the three `icon-sm` buttons (`size-7`, 28px) of one group — 84px. The
 * absent actions are **not** drawn as disabled buttons to make up the width:
 * what a row offers is what the store will take (decisions.md D4), and a
 * greyed-out Delete on a view that can never be deleted is an offer that was
 * never on the table.
 */
const ACTION_SLOT = 'w-21';

export interface ViewManagerRowProps {
  item: ViewInstanceSummary;
  manager: ViewManagerController;
  list: ViewListState;
  openDirtyId: string | null;
  /**
   * Where focus goes when this row's delete confirmation closes. The row may
   * be gone by then — that is what was confirmed — so there is nothing on it
   * to return to, and the manager hands down its own heading instead.
   */
  returnFocus?: RefObject<HTMLElement | null>;
  /** Moves the row one place, from the arrow keys on its handle. */
  onMove(step: -1 | 1): void;
  /** True while the library is carrying this row, so the arrows are its. */
  dragging?: boolean;
  elementRef?(element: HTMLElement | null): void;
  handleRef?(element: HTMLElement | null): void;
}

/**
 * One view in the manager: where it sits, what it is, what it is called, and
 * the writes it permits.
 *
 * Every button exists only where it is permitted rather than greyed out, so
 * what the row offers is exactly what the store will take. Renaming happens
 * in place: the title becomes an input and the row's other buttons stand down
 * until the edit is confirmed or dropped.
 */
export function ViewManagerRow({
  item,
  manager,
  list,
  openDirtyId,
  returnFocus,
  onMove,
  dragging,
  elementRef,
  handleRef,
}: ViewManagerRowProps) {
  const messages = useViewMessages();
  const [renaming, setRenaming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const Kind = KIND_ICON[item.kind];
  const can = manager.can.instance(item.id);
  const outcome = manager.outcomes.get(item.id);
  const busy = manager.pending !== null;
  const isDefault = list.preferences?.defaultInstanceId === item.id;

  // The one rename path, so the key and the button cannot drift apart: the
  // same trim, the same refusal of an empty name, the same write.
  const named = renaming === null ? '' : renaming.trim();
  const blocked = busy || named.length === 0;
  const confirmRename = () => {
    if (blocked) return;
    void manager.rename(item.id, named);
    setRenaming(null);
  };

  return (
    <div
      ref={elementRef}
      data-slot="view-manager-row"
      data-dragging={dragging ? '' : undefined}
      className="data-dragging:bg-muted flex flex-col gap-1 rounded-md"
    >
      <div className="flex min-w-0 items-center gap-2">
        {/* The order is the user's, and it is made by carrying a row rather
            than by clicking it up one step at a time. The handle leads the
            row because that is where a reader looks for one, and because the
            action slot on the right is about what becomes of the view rather
            than about where it sits. It is a list-wide permission, so either
            every row has one or none does, and the rows stay aligned. */}
        {manager.can.reorder && (
          <IconButton
            ref={handleRef}
            type="button"
            label={messages.label('label.manage.drag', {
              title: item.title,
            })}
            // Not while the row is in the air — see `ColumnRow`, which
            // carries the same handle for the same reason.
            silent={dragging}
            variant="ghost"
            size="icon-sm"
            className="shrink-0 cursor-grab"
            // Not a permission, so not an absence (D4): while a write is in
            // flight or this row's title is being edited, the row is busy
            // with something else and comes back as soon as it is done.
            disabled={busy || renaming !== null}
            onKeyDown={(event: KeyboardEvent) => {
              // While the library is carrying the row the arrows are its:
              // two handlers on one press would move the row twice.
              if (dragging) return;
              const step = STEP[event.key];
              if (!step) return;
              event.preventDefault();
              onMove(step);
            }}
          >
            <GripVerticalIcon />
          </IconButton>
        )}

        <Kind className="text-muted-foreground size-4 shrink-0" aria-hidden />

        {renaming === null ? (
          <span className="min-w-0 flex-1 truncate text-sm">{item.title}</span>
        ) : (
          <Input
            className="h-7 min-w-0 flex-1"
            aria-label={messages.label('label.save.title')}
            value={renaming}
            autoFocus
            onChange={event => setRenaming(event.target.value)}
            // A field with one obvious answer takes Enter for it — the ✓
            // beside it is the same call, not a different one — and Escape
            // for "never mind". Escape is stopped here rather than allowed
            // to bubble: the manager is a dialog, `useDismiss` listens for
            // the key on `document`, and an Escape that got that far closed
            // the whole manager and took the rename with it. React's
            // `stopPropagation` stops the native event too, so the key ends
            // at this input, where it was aimed.
            onKeyDown={event => {
              if (event.key === 'Enter') {
                event.preventDefault();
                confirmRename();
              } else if (event.key === 'Escape') {
                event.stopPropagation();
                setRenaming(null);
              }
            }}
          />
        )}

        {isSystemScope(item.scope) && (
          <Badge variant="secondary" className="shrink-0">
            {messages.label('label.scope.tag.system')}
          </Badge>
        )}
        {isDefault && (
          <Badge variant="outline" className="shrink-0">
            {messages.label('label.manage.default')}
          </Badge>
        )}

        {/* One group now that the order left this slot, so there is no
            between for `SPACE.GROUPS` to be. */}
        <div
          data-slot="view-manager-actions"
          className={cn('flex shrink-0 items-center', ACTION_SLOT)}
        >
          {renaming !== null ? (
            <ButtonGroup>
              <IconButton
                label={messages.label('label.manage.rename-confirm')}
                variant="ghost"
                size="icon-sm"
                disabled={blocked}
                onClick={confirmRename}
              >
                <CheckIcon />
              </IconButton>
              <IconButton
                label={messages.label('label.manage.rename-cancel')}
                variant="ghost"
                size="icon-sm"
                onClick={() => setRenaming(null)}
              >
                <XIcon />
              </IconButton>
            </ButtonGroup>
          ) : (
            <>
              {(manager.can.setDefault || can.rename || can.delete) && (
                <ButtonGroup
                  aria-label={messages.label('label.manage.view-group')}
                >
                  {manager.can.setDefault && (
                    <IconButton
                      label={messages.label(
                        isDefault
                          ? 'label.manage.unset-default'
                          : 'label.manage.set-default',
                      )}
                      variant="ghost"
                      size="icon-sm"
                      // It presses in and out, so it says which it is: the
                      // name tells a reader what the press would do, and
                      // this tells them what pressing it already did.
                      aria-pressed={isDefault}
                      disabled={busy}
                      onClick={() =>
                        void manager.setDefault(isDefault ? null : item.id)
                      }
                    >
                      {/* Filled rather than only marked. The attribute was
                          there and nothing was drawn from it, so pressing
                          "Open this one first" changed nothing on the star
                          itself and the badge beside the title was the only
                          thing that answered. */}
                      <StarIcon
                        data-default={isDefault || undefined}
                        className={isDefault ? 'fill-current' : undefined}
                      />
                    </IconButton>
                  )}
                  {can.rename && (
                    <IconButton
                      label={messages.label('label.manage.rename')}
                      variant="ghost"
                      size="icon-sm"
                      disabled={busy}
                      onClick={() => setRenaming(item.title)}
                    >
                      <PencilIcon />
                    </IconButton>
                  )}
                  {can.delete && (
                    <IconButton
                      label={messages.label('label.manage.delete')}
                      variant="ghost"
                      size="icon-sm"
                      disabled={busy}
                      onClick={() => setDeleting(true)}
                    >
                      <TrashIcon />
                    </IconButton>
                  )}
                </ButtonGroup>
              )}
            </>
          )}
        </div>
      </div>

      {outcome && (
        <ViewManagerOutcome
          state={outcome}
          manager={manager}
          list={list}
          outcomeKey={item.id}
          item={item}
          dirty={openDirtyId === item.id}
          returnFocus={returnFocus}
        />
      )}

      <DeleteDialog
        open={deleting}
        onOpenChange={setDeleting}
        item={item}
        dirty={openDirtyId === item.id}
        finalFocus={returnFocus}
        onConfirm={() => {
          void manager.delete(item.id);
          setDeleting(false);
        }}
      />
    </div>
  );
}

/** Arrow keys that move a row, and how far. */
const STEP: Record<string, -1 | 1 | undefined> = {
  ArrowUp: -1,
  ArrowDown: 1,
};

/**
 * A row that can be dragged, wired to the library.
 *
 * `group` is the audience the row is drawn under, which is how the two groups
 * stay apart: a row of the other group is not a drop target at all, so a
 * personal view cannot be carried in among the shared ones — the order would
 * be stored again, the revision spent, and the rows would sit exactly where
 * they were, because both lists draw personal views above shared ones
 * whatever order is stored.
 *
 * The optimistic plugin is left out on purpose. It reorders the DOM while the
 * pointer moves, which makes the indexes this component is rendered from
 * stale exactly when the drop is read; without it the library still draws the
 * drag preview, and the committed order is computed from the two ids the drop
 * reports.
 */
export function SortableViewManagerRow(
  props: ViewManagerRowProps & { index: number; group: string },
) {
  const { index, group, ...rest } = props;
  const { ref, handleRef, isDragging } = useSortable({
    id: rest.item.id,
    index,
    group,
    plugins: defaults =>
      defaults.filter(plugin => plugin !== OptimisticSortingPlugin),
  });

  return (
    <ViewManagerRow
      {...rest}
      dragging={isDragging}
      elementRef={ref}
      handleRef={handleRef}
    />
  );
}

/**
 * What a write that no open view owns came to, under the row that started it.
 * The order and the default are one record rather than a row, so theirs is
 * drawn at the top of the dialog with no row above it.
 *
 * Reloading a preference conflict does not replay the intent (design/management.md):
 * the list comes back at the stored revision and the user presses again, so
 * the line stays until they do something with it.
 */
export function ViewManagerOutcome({
  state,
  manager,
  list,
  outcomeKey,
  item,
  dirty = false,
  returnFocus,
}: {
  state: WriteState;
  manager: ViewManagerController;
  list: ViewListState;
  outcomeKey: string;
  /** The row this outcome belongs to; the preferences record has none. */
  item?: ViewInstanceSummary;
  /** True when this is the open view and it has unsaved edits. */
  dirty?: boolean;
  /** Where focus goes when the second delete confirmation closes. */
  returnFocus?: RefObject<HTMLElement | null>;
}) {
  const [reconfirming, setReconfirming] = useState(false);
  // Commands run one at a time, so any write in flight — this row's or
  // another's — is one a recovery button would queue behind.
  const busy = manager.pending !== null;

  // A reload of a preference conflict settled it and kept what the user meant
  // (design/management.md), so what is offered then is that intent once more
  // rather than a recovery of a write the engine no longer holds — the button
  // used to call one that could only answer "nothing to recover".
  const resubmittable =
    state.kind === 'conflict' && manager.canResubmit(outcomeKey);

  // A delete that conflicted is the one overwrite that is asked about twice:
  // the first confirmation was about the view as it stood, and what the
  // conflict reports is a view that has changed since — it may now be shared,
  // and it is certainly not what was confirmed.
  const target =
    state.kind === 'conflict' &&
    !resubmittable &&
    state.payload.action === 'delete'
      ? refreshed(state.remote, item)
      : null;

  return (
    <>
      <OutcomeActions
        surface="row"
        state={state}
        pending={busy}
        actions={{
          resubmit: resubmittable
            ? () => void manager.resubmit(outcomeKey)
            : undefined,
          reload: () => {
            void manager.resolveConflict(outcomeKey, 'reload');
            list.reload();
          },
          overwrite: () => {
            if (target) setReconfirming(true);
            else void manager.resolveConflict(outcomeKey, 'overwrite');
          },
          retry: () => void manager.retry(outcomeKey),
          leave: () => manager.abandon(outcomeKey),
          dismiss: () => manager.abandon(outcomeKey),
        }}
      />
      {target && (
        <DeleteDialog
          open={reconfirming}
          onOpenChange={setReconfirming}
          item={target}
          dirty={dirty}
          finalFocus={returnFocus}
          onConfirm={() => {
            void manager.resolveConflict(outcomeKey, 'overwrite');
            setReconfirming(false);
          }}
        />
      )}
    </>
  );
}

/**
 * The view a delete conflict is really about: the server's copy, which is
 * what the second confirmation has to describe — a view that became shared
 * while the dialog was open costs other people their view too, and the first
 * confirmation never said so. The row's own summary stands in when what came
 * back was not an instance at all.
 */
function refreshed(
  remote: ViewInstance | ViewPreferences,
  item: ViewInstanceSummary | undefined,
): ViewInstanceSummary | null {
  if ('config' in remote) return toSummary(remote);
  return item ?? null;
}
