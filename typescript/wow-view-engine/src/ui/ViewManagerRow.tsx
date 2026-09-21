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

import { useState } from 'react';
import { cn } from 'cn';
import {
  ArrowDownIcon,
  ArrowUpIcon,
  CheckIcon,
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
import { Button } from './components/button.js';
import { ButtonGroup } from './components/button-group.js';
import { Input } from './components/input.js';
import { DeleteDialog } from './DeleteDialog.js';
import { KIND_ICON } from './kinds.js';
import { SPACE } from './layout.js';
import { useViewMessages } from './MessagesProvider.js';
import { OutcomeActions } from './OutcomeActions.js';

/**
 * The width every row gives its actions, whether or not it has all of them.
 *
 * A column of icons looks like a column, and a user reads it as one: what
 * sits under "Move up" on the row above must be "Move up" here too. Rows do
 * not all carry the same actions — a system view has no rename and no delete
 * — so with the cluster sized to its contents and pushed right, a system
 * row's "Move up" landed exactly where every other row's "Set default" was,
 * and its "Set default" where their "Delete" was. Three icons, all of them
 * lying about what they do.
 *
 * The fix is a slot as wide as the fullest row and contents left-aligned
 * inside it: five `icon-sm` buttons (`size-7`, 28px) plus the one
 * `SPACE.GROUPS` between the two groups — 148px. The absent actions are
 * **not** drawn as disabled buttons to make up the width: what a row offers
 * is what the store will take (decisions.md D4), and a greyed-out Delete on
 * a view that can never be deleted is an offer that was never on the table.
 */
const ACTION_SLOT = 'w-37';

/**
 * One view in the manager: what it is, what it is called, and the writes it
 * permits.
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
}: {
  item: ViewInstanceSummary;
  manager: ViewManagerController;
  list: ViewListState;
  openDirtyId: string | null;
}) {
  const messages = useViewMessages();
  const [renaming, setRenaming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const Kind = KIND_ICON[item.kind];
  const can = manager.can.instance(item.id);
  const outcome = manager.outcomes.get(item.id);
  const busy = manager.pending !== null;
  const isDefault = list.preferences?.defaultInstanceId === item.id;

  return (
    <div data-slot="view-manager-row" className="flex flex-col gap-1">
      <div className="flex min-w-0 items-center gap-2">
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

        <div
          data-slot="view-manager-actions"
          className={cn(
            'flex shrink-0 items-center',
            ACTION_SLOT,
            SPACE.GROUPS,
          )}
        >
          {renaming !== null ? (
            <ButtonGroup>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={messages.label('label.manage.rename-confirm')}
                disabled={busy || renaming.trim().length === 0}
                onClick={() => {
                  void manager.rename(item.id, renaming.trim());
                  setRenaming(null);
                }}
              >
                <CheckIcon />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={messages.label('label.manage.rename-cancel')}
                onClick={() => setRenaming(null)}
              >
                <XIcon />
              </Button>
            </ButtonGroup>
          ) : (
            <>
              {manager.can.reorder && (
                <ButtonGroup
                  aria-label={messages.label('label.manage.order-group')}
                >
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={messages.label('label.manage.move-up')}
                    // The group the row is drawn in is what it moves within:
                    // the arrows go dead at the top and bottom of that group,
                    // because a swap across the boundary would store a new
                    // order and leave the screen exactly as it was.
                    disabled={busy || !manager.canMove(item.id, 'up')}
                    onClick={() => void manager.move(item.id, 'up')}
                  >
                    <ArrowUpIcon />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    aria-label={messages.label('label.manage.move-down')}
                    disabled={busy || !manager.canMove(item.id, 'down')}
                    onClick={() => void manager.move(item.id, 'down')}
                  >
                    <ArrowDownIcon />
                  </Button>
                </ButtonGroup>
              )}
              {(manager.can.setDefault || can.rename || can.delete) && (
                <ButtonGroup
                  aria-label={messages.label('label.manage.view-group')}
                >
                  {manager.can.setDefault && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={messages.label(
                        isDefault
                          ? 'label.manage.unset-default'
                          : 'label.manage.set-default',
                      )}
                      disabled={busy}
                      onClick={() =>
                        void manager.setDefault(isDefault ? null : item.id)
                      }
                    >
                      <StarIcon data-default={isDefault || undefined} />
                    </Button>
                  )}
                  {can.rename && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={messages.label('label.manage.rename')}
                      disabled={busy}
                      onClick={() => setRenaming(item.title)}
                    >
                      <PencilIcon />
                    </Button>
                  )}
                  {can.delete && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={messages.label('label.manage.delete')}
                      disabled={busy}
                      onClick={() => setDeleting(true)}
                    >
                      <TrashIcon />
                    </Button>
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
        />
      )}

      <DeleteDialog
        open={deleting}
        onOpenChange={setDeleting}
        item={item}
        dirty={openDirtyId === item.id}
        onConfirm={() => {
          void manager.delete(item.id);
          setDeleting(false);
        }}
      />
    </div>
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
}: {
  state: WriteState;
  manager: ViewManagerController;
  list: ViewListState;
  outcomeKey: string;
  /** The row this outcome belongs to; the preferences record has none. */
  item?: ViewInstanceSummary;
  /** True when this is the open view and it has unsaved edits. */
  dirty?: boolean;
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
