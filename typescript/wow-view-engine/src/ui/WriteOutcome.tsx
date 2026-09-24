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

import { useEffect, useRef, useState } from 'react';
import type {
  ViewConfig,
  ViewInstance,
  ViewPreferences,
} from '../model/index.js';
import type {
  ConflictChoice,
  WriteAction,
  WritePayload,
  WriteState,
} from '../runtime/index.js';
import type { SaveCommands } from '../react/index.js';
import { ConflictConfirm } from './ConflictConfirm.js';
import { OutcomeActions, RefusalLine } from './OutcomeActions.js';
import { SaveAsDialog } from './SaveAsDialog.js';

/**
 * What a host is told about the writes of one open view.
 *
 * Declared here, once, because this is the component that calls them: a
 * write's outcome is where a save, a rename or a delete is finally reported
 * from, whether it landed the first time or was recovered afterwards. The
 * title bar passes the group through, and a component that reports only part
 * of it picks that part out of this type rather than restating it — a prop a
 * component never calls is a promise to the host that nothing keeps.
 */
export interface ViewWriteCallbacks {
  /** Called with the instance a save produced, so a host can open it. */
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
  /**
   * Called with the instance a recovered rename produced. Renaming is
   * started from the view manager, and what became of it is reported here
   * too, so a host keeps one place to learn what landed.
   */
  onRenamed?(instance: ViewInstance): void;
  /** Called when a recovered delete landed: this view is gone. */
  onDeleted?(): void;
  /** Called when a recovered write (retry, overwrite, reload) landed. */
  onRecovered?(action: WriteAction): void;
}

export interface WriteOutcomeProps extends ViewWriteCallbacks {
  commands: SaveCommands;
  /** The view's title, so a copy out of a conflict can be offered from it. */
  title: string;
}

/**
 * The config a write was carrying, as the user last had it. A rename and a
 * delete carry none — there is nothing of theirs to put beside the server's.
 */
function localConfig(payload: WritePayload): ViewConfig | null {
  switch (payload.action) {
    case 'save':
      return payload.config;
    case 'create':
      return payload.input.config;
    default:
      return null;
  }
}

/** The server's config, when what it sent back was an instance at all. */
function remoteConfig(
  remote: ViewInstance | ViewPreferences,
): ViewConfig | null {
  return 'config' in remote ? remote.config : null;
}

/**
 * What became of the last write of the open view, and the way out of it.
 *
 * The line itself is `OutcomeActions`, which the manager's rows draw too;
 * what lives here is the part only an open view has — a conflict it can make
 * a copy out of, and the landing a recovered write has to be reported as.
 */
export function WriteOutcome({
  commands,
  title,
  onSaved,
  onCreated,
  onRenamed,
  onDeleted,
  onRecovered,
}: WriteOutcomeProps) {
  const [confirming, setConfirming] = useState<ConflictChoice | null>(null);
  const [copying, setCopying] = useState(false);
  // The conflict a copy is being made out of, captured when the dialog opens.
  // Landing the copy is a write of its own, and from the moment it has an
  // outcome the runtime reports *that* one — so this captured state is the
  // only address the original conflict still has. A ref rather than state:
  // nothing renders from it, and it is read from an effect that must not
  // cause a render of its own.
  const original = useRef<WriteState | null>(null);
  const { write, error, pending } = commands.state;

  // Settled as soon as the runtime stops reporting it, whichever way the copy
  // went: it landed (the outcome slot is empty now), or it came back rejected,
  // unknown or in conflict itself (the slot holds the copy's). Either way the
  // original is no longer addressable from the screen, and leaving it would
  // strand a write in `engine.pendingWrites()` for the rest of the session.
  // A copy the engine refused before dispatching changes nothing here — the
  // runtime still reports the original, and it stays the user's to answer.
  useEffect(() => {
    const held = original.current;
    if (held === null || write === held) return;
    original.current = null;
    commands.abandon(held);
  }, [commands, write]);

  /**
   * A recovered write lands like the original one would have: a recovered
   * create or save opens what it made, a rename keeps the instance current,
   * a delete lets the view go. Every landing also reports through
   * onRecovered, which is where a host that wires nothing else stays fresh.
   * A reload is not a landing: the server's state was adopted, so nothing is
   * opened or let go of — the list may still have moved.
   */
  const notify = (
    action: WriteAction | undefined,
    instance: ViewInstance | null,
    reloaded = false,
  ) => {
    if (!action) return;
    if (reloaded) {
      onRecovered?.(action);
      return;
    }
    switch (action) {
      case 'delete':
        onDeleted?.();
        onRecovered?.(action);
        return;
      case 'rename':
        if (instance) onRenamed?.(instance);
        onRecovered?.(action);
        return;
      default:
        if (instance) onSaved?.(instance);
        onRecovered?.(action);
    }
  };

  if (write?.kind === 'conflict') {
    const resolve = (choice: ConflictChoice) => {
      const action = write.payload.action;
      setConfirming(null);
      void commands
        .resolveConflict(choice)
        .then(
          result =>
            result.landed &&
            notify(action, result.instance, choice === 'reload'),
        );
    };
    return (
      <>
        <OutcomeActions
          state={write}
          pending={pending}
          // Each way out is offered only where it can be taken: a copy needs
          // somewhere to create it, an overwrite needs the right to write.
          actions={{
            reload: () => setConfirming('reload'),
            copy: commands.can.saveAs
              ? () => {
                  original.current = write;
                  setCopying(true);
                }
              : undefined,
            overwrite: commands.can.save
              ? () => setConfirming('overwrite')
              : undefined,
          }}
        />

        <ConflictConfirm
          choice={confirming}
          local={localConfig(write.payload)}
          remote={remoteConfig(write.remote)}
          onOpenChange={open => setConfirming(open ? confirming : null)}
          onConfirm={resolve}
        />

        <SaveAsDialog
          open={copying}
          onOpenChange={open => {
            setCopying(open);
            // Closed without an answer: the conflict is still on screen and
            // still the user's to settle, so nothing is given up.
            if (!open) original.current = null;
          }}
          commands={commands}
          title={title}
          // The copy is how this conflict ends, so the write it came out of
          // is settled here rather than left pending: the host is about to
          // open the copy, which releases this runtime, and a pending write
          // whose runtime is disposed can never be retried, overwritten or
          // abandoned by anyone again. Done here rather than left to the
          // effect above, which the unmount this callback causes would race.
          onSaved={saved => {
            original.current = null;
            commands.abandon(write);
            onSaved?.(saved);
            onCreated?.(saved);
          }}
        />
      </>
    );
  }

  if (write?.kind === 'unknown')
    return (
      <OutcomeActions
        state={write}
        pending={pending}
        actions={{
          retry: () => {
            const action = write.payload.action;
            void commands
              .retry()
              .then(result => result.landed && notify(action, result.instance));
          },
          leave: () => commands.abandon(),
        }}
      />
    );

  if (write?.kind === 'rejected')
    // The store never took it, so there is nothing to retry or overwrite —
    // only the reason, and a way to take it down. Dismissing matters: the
    // refusal stays in the engine's pending writes until it is settled,
    // and the line would sit over the view for the rest of the session
    // while the user fixes what it complained about.
    return (
      <OutcomeActions
        state={write}
        pending={pending}
        actions={{ dismiss: () => commands.abandon(write) }}
      />
    );

  // A command the engine refused before dispatching holds nothing to settle,
  // so it is the one line with no way out and needs none.
  return error ? <RefusalLine issue={error} /> : null;
}
