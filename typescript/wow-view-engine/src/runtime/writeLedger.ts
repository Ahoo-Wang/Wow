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
  isViewStoreError,
  type ViewInstance,
  type ViewPreferences,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import type { ViewStore, WriteContext } from '../store/ViewStore.js';
import type { ManagedViewRuntime, ViewRuntime } from './viewRuntime.js';
import type { ViewChange } from './viewChanges.js';
import {
  ViewCommandError,
  ViewWriteError,
  type WriteHandle,
  type WritePayload,
  type WriteState,
} from './write.js';

/** What a write command is addressed to: an open view, or a handle. */
export type WriteTarget = ViewRuntime | WriteHandle;

export type ConflictChoice = 'reload' | 'overwrite';

/** Everything a write can hand back, by action. */
export type WriteResult = ViewInstance | ViewPreferences | void;

/**
 * What the ledger cannot reach on its own.
 *
 * A write's outcome belongs to the ledger, but a *confirmed* write also moves
 * things the ledger does not own: the engine's summary and preference caches,
 * and the other open views of the same instance. The engine hands those over
 * as these few calls rather than letting the ledger hold its registry.
 */
export interface WriteLedgerHost {
  /** The port every write goes to. */
  readonly store: ViewStore;
  /** Idempotency keys; overridden in tests to keep them readable. */
  newId?(): string;
  /** Remembers the summary of an instance the store has just confirmed. */
  noteInstance(instance: ViewInstance): void;
  /** Forgets a deleted instance and closes every open view of it, `owner` first. */
  dropInstance(id: string, owner: ManagedViewRuntime | undefined): void;
  /** Remembers preferences the store has just confirmed. */
  notePreferences(definitionId: string, preferences: ViewPreferences): void;
  /** Re-reads preferences through the host, so its cache moves with them too. */
  readPreferences(definitionId: string): Promise<ViewPreferences>;
  /** Open views whose baseline is this instance. */
  holders(id: string): readonly ManagedViewRuntime[];
  /**
   * Says that a confirmed write changed what a definition's list holds. It
   * hangs off the same place the effects do, so a retry and an overwrite
   * announce what the first attempt would have.
   */
  noteChange(change: ViewChange): void;
}

/**
 * The write ledger: one account of every write that left and has not been
 * settled, keyed by its `requestId`.
 *
 * Every write leaves through `dispatch`, so the default UI and a hand-built
 * one behave the same, and every non-success outcome lands in the same three
 * recovery actions: `retryWrite`, `abandonWrite`, `resolveConflict`.
 *
 * What the ledger keeps is the *body* of the write rather than the draft it
 * came from, which is what makes those three honest: a retry and an overwrite
 * replay the original intent, so what the user asked for survives whatever
 * they edited while the request was in flight.
 */
export class WriteLedger {
  private readonly host: WriteLedgerHost;
  private readonly writes = new Map<string, WriteState>();
  /** Write targets with a request in flight; see `dispatch`. */
  private readonly inFlight = new Set<string>();
  private readonly owners = new Map<string, ManagedViewRuntime>();
  private readonly newId: () => string;

  constructor(host: WriteLedgerHost) {
    this.host = host;
    this.newId = host.newId ?? (() => crypto.randomUUID());
  }

  /** Writes still waiting for a decision, by handle id. */
  pendingWrites(): ReadonlyMap<string, WriteState> {
    return this.writes;
  }

  /** A new logical write: a fresh `requestId`, then the one path out. */
  dispatch(
    payload: WritePayload,
    runtime: ManagedViewRuntime | undefined,
  ): Promise<WriteResult> {
    return this.replay(payload, this.newId(), runtime);
  }

  /** Replays the original intent under its original `requestId`. */
  async retryWrite(target: WriteTarget): Promise<WriteResult> {
    const { requestId, state } = this.requireWrite(target);
    return this.replay(state.payload, requestId, this.owners.get(requestId));
  }

  /** Drops the outcome and keeps the draft; a later save is a new intent. */
  abandonWrite(target: WriteTarget): void {
    const { requestId } = this.requireWrite(target);
    this.settle(requestId);
  }

  /**
   * `reload` takes the server's state, losing the draft; `overwrite` replays
   * the original intent against the revision the conflict reported, which is a
   * new logical write and so takes a new `requestId`.
   */
  async resolveConflict(
    target: WriteTarget,
    choice: ConflictChoice,
  ): Promise<WriteResult> {
    const { requestId, state } = this.requireWrite(target);
    if (state.kind !== 'conflict')
      throw new ViewCommandError(
        issue('view.write.not-a-conflict', [], { kind: state.kind }),
      );

    const runtime = this.owners.get(requestId);
    if (choice === 'reload') {
      this.settle(requestId);
      return this.reload(state, runtime);
    }
    this.settle(requestId);
    return this.dispatch(withRevision(state.payload, state.remote), runtime);
  }

  /**
   * The one place a write leaves the engine. Success clears the outcome;
   * anything else is recorded, attached to the owning runtime and raised as a
   * `ViewWriteError` carrying its own handle.
   *
   * It takes the `requestId` rather than minting one, because a retry is the
   * same logical write and has to carry the key the first attempt used.
   */
  private async replay(
    payload: WritePayload,
    requestId: string,
    runtime: ManagedViewRuntime | undefined,
  ): Promise<WriteResult> {
    // One write at a time per target. Two saves of one view would carry the
    // same expected revision, so the second reports a conflict the user caused
    // by clicking twice; two first saves would each create, leaving a duplicate
    // instance and a runtime bound to only one of them. The idempotent
    // `requestId` cannot help, because each command mints its own: it dedupes
    // a retry of one write, not two writes that mean the same thing.
    const key = writeKey(payload, runtime);
    if (this.inFlight.has(key))
      throw new ViewCommandError(
        issue('view.write.in-flight', [], { action: payload.action }),
      );
    this.requireNoUnknownWrite(key, requestId);
    this.inFlight.add(key);

    const context: WriteContext = { requestId };
    if (runtime) this.owners.set(requestId, runtime);
    try {
      const result = await this.send(payload, context);
      this.applyEffect(payload, result, runtime);
      this.settle(requestId);
      return result;
    } catch (error) {
      const state = await this.toWriteState(error, requestId, payload);
      this.writes.set(requestId, state);
      runtime?.setWrite(state);
      throw new ViewWriteError(state);
    } finally {
      this.inFlight.delete(key);
    }
  }

  /**
   * What a confirmed write changes. It hangs off the payload rather than the
   * command, so replaying an intent as a retry or an overwrite advances the
   * same baseline the first attempt would have.
   */
  private applyEffect(
    payload: WritePayload,
    result: WriteResult,
    runtime: ManagedViewRuntime | undefined,
  ): void {
    switch (payload.action) {
      case 'create': {
        const instance = result as ViewInstance;
        this.host.noteInstance(instance);
        if (payload.intent === 'first-save') runtime?.markSaved(instance);
        this.announce('create', instance);
        return;
      }
      case 'save':
      case 'rename': {
        const instance = result as ViewInstance;
        this.host.noteInstance(instance);
        // Every open view of this instance moves to the new baseline, not
        // only the one the command came through: the same view open twice
        // would otherwise keep a revision nobody can write against. Only the
        // view this write belongs to has its outcome settled; another's
        // unsettled write is still its own to retry or abandon.
        runtime?.markSaved(instance);
        for (const holder of this.host.holders(instance.id))
          if (holder !== runtime) holder.moveBaseline(instance);
        this.announce(payload.action, instance);
        return;
      }
      case 'delete':
        // Preferences keep the id; a later reorder or default cleans it up.
        this.host.dropInstance(payload.id, runtime);
        this.host.noteChange({
          definitionId: payload.definitionId,
          kind: 'delete',
          id: payload.id,
        });
        return;
      case 'preferences':
        this.host.notePreferences(
          payload.definitionId,
          result as ViewPreferences,
        );
    }
  }

  /** The three actions that hand back an instance say so the same way. */
  private announce(
    kind: 'create' | 'save' | 'rename',
    instance: ViewInstance,
  ): void {
    this.host.noteChange({
      definitionId: instance.definitionId,
      kind,
      id: instance.id,
    });
  }

  /** Each action against its own method on the port. */
  private send(
    payload: WritePayload,
    context: WriteContext,
  ): Promise<WriteResult> {
    const store = this.host.store;
    switch (payload.action) {
      case 'create':
        return store.create(payload.input, context);
      case 'save':
        return store.save(
          payload.id,
          payload.config,
          payload.revision,
          context,
        );
      case 'rename':
        return store.rename(
          payload.id,
          payload.title,
          payload.revision,
          context,
        );
      case 'delete':
        return store.delete(payload.id, payload.revision, context);
      case 'preferences':
        return store.setPreferences(
          payload.definitionId,
          payload.next,
          context,
        );
    }
  }

  /** How a thrown write becomes an outcome the three recovery actions read. */
  private async toWriteState(
    error: unknown,
    requestId: string,
    payload: WritePayload,
  ): Promise<WriteState> {
    if (!isViewStoreError(error))
      // Anything that does not speak the port's language is treated as an
      // unknown outcome rather than a failure. It may well be a bug in the
      // adapter that will fail again on retry, and saying "unknown" about it
      // is then misleading — but the other mistake is worse: a write that
      // reached the server, reported as failed, is a view the user saves a
      // second time. Only the store can tell these apart, and it does so by
      // raising a `ViewStoreError`.
      return { kind: 'unknown', requestId, payload };

    switch (error.code) {
      case 'UNAVAILABLE':
        return { kind: 'unknown', requestId, payload };
      case 'CONFLICT': {
        // Which of the two the error may carry is decided by what was being
        // written, not by what happens to be on the error: a preference
        // write reads `preferences` and an instance write reads `instance`,
        // so a store that filled in the wrong one reports nothing rather
        // than being believed.
        const remote =
          (payload.action === 'preferences'
            ? error.preferences
            : error.instance) ?? (await this.fetchRemote(payload));
        if (!remote)
          return {
            kind: 'rejected',
            requestId,
            payload,
            issue: issue('view.write.conflict-unreadable', []),
          };
        // A delete that conflicts is answered by confirming again (§7.4), so
        // the summary the user confirms against is the one the store now
        // holds rather than the title and revision they asked to delete.
        if (payload.action === 'delete')
          this.host.noteInstance(remote as ViewInstance);
        return { kind: 'conflict', remote, requestId, payload };
      }
      default:
        return {
          kind: 'rejected',
          requestId,
          payload,
          issue: issue(`view.write.${error.code.toLowerCase()}`, [], {
            reason: error.message,
          }),
        };
    }
  }

  /** A store may report a conflict without the state it holds; ask for it. */
  private async fetchRemote(
    payload: WritePayload,
  ): Promise<ViewInstance | ViewPreferences | null> {
    const store = this.host.store;
    try {
      if (payload.action === 'preferences')
        return await store.getPreferences(payload.definitionId);
      if (payload.action === 'create') return null;
      return await store.get(payload.id);
    } catch {
      return null;
    }
  }

  /** The `reload` half of `resolveConflict`: take what the server holds. */
  private async reload(
    state: Extract<WriteState, { kind: 'conflict' }>,
    runtime: ManagedViewRuntime | undefined,
  ): Promise<WriteResult> {
    if (state.payload.action === 'preferences')
      return this.host.readPreferences(state.payload.definitionId);
    const remote = state.remote as ViewInstance;
    // Only a `save` was carrying a config, so only reloading that one means
    // taking the server's config and dropping the draft. A rename or a delete
    // carries no config (§7.1), and the edits the user has not saved yet are
    // not theirs to discard: the baseline moves and the draft stays.
    if (state.payload.action === 'save') runtime?.adoptSaved(remote);
    else runtime?.moveBaseline(remote);
    this.host.noteInstance(remote);
    return remote;
  }

  /** The entry a recovery action names, whether by open view or by handle. */
  private requireWrite(target: WriteTarget): {
    requestId: string;
    state: WriteState;
  } {
    const requestId = isRuntime(target)
      ? target.getSnapshot().write?.requestId
      : target.id;
    const state = requestId ? this.writes.get(requestId) : undefined;
    if (!requestId || !state)
      throw new ViewCommandError(issue('view.write.not-pending', []));
    return { requestId, state };
  }

  /**
   * An unknown outcome is a write that may have landed. Sending another to
   * the same target before it is retried or abandoned is how a first save
   * ends up as two instances, so a new intent waits; only the replay of the
   * unknown write itself, under its own `requestId`, goes through.
   */
  private requireNoUnknownWrite(key: string, requestId: string): void {
    for (const [pendingId, pending] of this.writes) {
      if (pendingId === requestId || pending.kind !== 'unknown') continue;
      if (writeKey(pending.payload, this.owners.get(pendingId)) !== key)
        continue;
      throw new ViewCommandError(
        issue('view.write.unknown-pending', [], {
          action: pending.payload.action,
        }),
      );
    }
  }

  /** Closes the account for one write: nothing left to retry or abandon. */
  private settle(requestId: string): void {
    this.writes.delete(requestId);
    const owner = this.owners.get(requestId);
    // Only if the runtime is still reporting *this* write. It may have moved
    // on to a later one — a copy made out of a conflict is a write of its own,
    // and settling the conflict behind it would take the copy's outcome off
    // the screen while the engine went on holding it.
    if (owner?.getSnapshot().write?.requestId === requestId)
      owner.setWrite(null);
    this.owners.delete(requestId);
  }
}

function isRuntime(target: WriteTarget): target is ViewRuntime {
  return typeof (target as ViewRuntime).getSnapshot === 'function';
}

/**
 * What a write contends for. A runtime is its own target — the design allows
 * one in-flight write per open view, whatever the command — and a write with
 * no runtime behind it contends for the instance or the preferences it names.
 */
function writeKey(
  payload: WritePayload,
  runtime: ManagedViewRuntime | undefined,
): string {
  if (runtime) return `runtime:${runtime.id}`;
  switch (payload.action) {
    case 'preferences':
      return `preferences:${payload.definitionId}`;
    case 'create':
      return `create:${payload.input.definitionId}:${payload.input.title}`;
    default:
      return `instance:${payload.id}`;
  }
}

/** The same intent, expecting the revision a conflict reported instead. */
function withRevision(
  payload: WritePayload,
  remote: ViewInstance | ViewPreferences,
): WritePayload {
  switch (payload.action) {
    case 'preferences':
      return {
        ...payload,
        next: { ...payload.next, revision: remote.revision },
      };
    case 'create':
      return payload;
    default:
      return { ...payload, revision: remote.revision };
  }
}
