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

/**
 * One instance write of the `WowViewStore`, from the place the view is at to
 * the answer read back: sent with the version it expects and, for a move,
 * waited for until the saga has moved it (`write`); a landed write read back
 * at the version it left (`landed`); a move to where the view already is
 * answered as it is (`unchanged`); a stale write refused with the view as it
 * is now (`conflict`). What it reads of the store — where each view was last
 * seen, the read by id, a system view's version, a command's options — it
 * reads through `ViewWritesHost`.
 */

import {
  type CommandOptions,
  type CommandResult,
  WaitPlan,
} from '@ahoo-wang/wow-client';
import {
  ViewStoreError,
  type ViewInstance,
  type WriteOptions,
} from '@ahoo-wang/wow-view-engine';
import { SHARED_OWNER_ID, SYSTEM_OWNER_ID, type Place } from './paths.js';
import {
  ViewStoreContract,
  viewStoreWait,
} from './generated/view-store/contract.js';
import type { ViewStateClient } from './generated/view-store/view/queryClient.js';
import { boardTitles, Failure, guard, request } from './errors.js';
import { scopeAt, toInstance } from './wire.js';

/**
 * What a share or a claim waits for: the saga that moves the view to its
 * new owner, then that move's snapshot. The view's own commit changes its
 * audience; the owner, which reads and writes go by, changes one version
 * later (`ViewAudienceSaga`).
 */
const AUDIENCE_WAIT = WaitPlan.of(
  viewStoreWait.handled('ViewAudienceSaga.onAudienceChanged'),
  viewStoreWait.snapshot(),
);

/** One instance write, as sent from the place the view is at. */
export interface InstanceWrite {
  /** Sends it with the options the store fills. */
  send(options: CommandOptions): Promise<CommandResult>;
  /** The place whose `Wow-Scope` it is sent with. */
  sentFrom: Place;
  /** Where the view is once it lands; `null` for a delete. */
  landsAt: Place | null;
  /** A share or a claim: it moves the view, waiting for the move. */
  moves?: boolean;
}

/** What a `ViewWrites` reads of the store it writes for. */
export interface ViewWritesHost {
  readonly states: ViewStateClient;
  /** Where each view was last seen, by id. */
  readonly places: Map<string, Place>;
  read(
    id: string,
    signal: AbortSignal | undefined,
  ): Promise<{ place: Place; instance: ViewInstance }>;
  systemVersion(
    id: string,
    revision: string,
    signal: AbortSignal | undefined,
  ): Promise<number>;
  commandOptions(
    place: Place,
    context: WriteOptions,
    more?: Partial<CommandOptions>,
  ): CommandOptions;
}

export class ViewWrites {
  constructor(private readonly host: ViewWritesHost) {}

  /**
   * One instance write: to the place the view is at, then the answer read
   * back. A view remembered at a place it has since left (another tab shared
   * it) is looked up again and the write sent once more from where it is.
   * A move refused because the view already has the audience it asks for
   * changes nothing: the view is answered as it is, once its revision
   * matches.
   */
  write(
    id: string,
    revision: string,
    context: WriteOptions,
    plan: (place: Place) => InstanceWrite,
  ): Promise<ViewInstance | undefined> {
    return guard(async () => {
      const { signal } = context;
      const remembered = this.host.places.get(id);
      let place = remembered ?? (await this.host.read(id, signal)).place;
      let relocated = remembered === undefined;
      for (;;) {
        const write = plan(place);
        const options = this.host.commandOptions(write.sentFrom, context, {
          expectedVersion:
            place === 'system'
              ? await this.host.systemVersion(id, revision, signal)
              : versionOf(revision),
          ...(write.moves ? { wait: AUDIENCE_WAIT } : {}),
        });
        let result: CommandResult;
        try {
          result = await request(() => write.send(options));
        } catch (thrown) {
          if (!(thrown instanceof Failure)) throw thrown;
          if (
            write.moves &&
            place === write.landsAt &&
            (thrown.code === 'NOT_FOUND' || thrown.code === 'FORBIDDEN')
          )
            return this.unchanged(id, revision, place, signal);
          if (
            !relocated &&
            (thrown.code === 'NOT_FOUND' || thrown.code === 'FORBIDDEN')
          ) {
            relocated = true;
            const found = (await this.host.read(id, signal)).place;
            if (found !== place) {
              place = found;
              continue;
            }
          }
          if (thrown.code === 'CONFLICT')
            throw await this.conflict(id, thrown, signal);
          throw refusal(thrown);
        }
        // Landed: what goes wrong reading it back is never a reason to send
        // it again.
        return this.landed(id, write, result, context);
      }
    });
  }

  /**
   * A landed write's answer: the view read back at the version it left
   * (`GET …/{id}/state?version=`). A system view's revision is its content
   * hash, so it is read by id as it is now.
   *
   * A move is two commits: the view's own (its audience), then the saga's
   * (its owner). Waited for, the result's version is the second; a
   * duplicate answers the first, so the move is read one version later,
   * where the owner it names must be the one it moved to. Anything else
   * (another writer between the two) answers the view as it is now.
   */
  async landed(
    id: string,
    write: InstanceWrite,
    result: CommandResult,
    context: WriteOptions,
  ): Promise<ViewInstance | undefined> {
    const { landsAt } = write;
    if (landsAt === null) return undefined;
    this.host.places.set(id, landsAt);
    const { signal } = context;
    const read = typeof result.version === 'number' ? result.version : null;
    if (landsAt !== 'system' && read !== null)
      try {
        const version = write.moves && result.duplicate ? read + 1 : read;
        const view = await request(() =>
          this.host.states.get(id, {
            version,
            ...optionsAt(landsAt),
            ...(signal === undefined ? {} : { signal }),
          }),
        );
        if (!write.moves || ownedAt(view.ownerId) === landsAt)
          return toInstance({ id, version: view.version, state: view.state });
      } catch {
        // Moved on since (another tab moved it): answered as it is.
      }
    return (await this.host.read(id, signal)).instance;
  }

  /** A move to where the view already is: the view as it is, at `revision`. */
  private async unchanged(
    id: string,
    revision: string,
    place: Place,
    signal: AbortSignal | undefined,
  ): Promise<ViewInstance> {
    const { instance } = await this.host.read(id, signal);
    if (instance.scope !== place || instance.revision !== revision)
      throw new ViewStoreError(
        'CONFLICT',
        `View ${id} has moved on from revision ${revision}`,
        { instance },
      );
    return instance;
  }

  /** A stale write's refusal, carrying the view as it is now. */
  private async conflict(
    id: string,
    failure: Failure,
    signal: AbortSignal | undefined,
  ): Promise<ViewStoreError> {
    const { instance } = await this.host.read(id, signal);
    return new ViewStoreError('CONFLICT', failure.message, {
      ...failure.held(),
      instance,
    });
  }
}

/** The request options of `place`: its `Wow-Scope`, when it has one. */
function optionsAt(place: Place): { scope?: ReturnType<typeof scopeAt> } {
  const scope = scopeAt(place);
  return scope === undefined ? {} : { scope };
}

/** The place of a view its owner says: a reserved owner, or a user's own. */
function ownedAt(ownerId: string | null | undefined): Place {
  return ownerId === SHARED_OWNER_ID
    ? 'shared'
    : ownerId === SYSTEM_OWNER_ID
      ? 'system'
      : 'personal';
}

/** The aggregate version a revision of this store names. */
export function versionOf(revision: string): number {
  const version = Number(revision);
  if (!Number.isInteger(version) || version < 0)
    throw new ViewStoreError(
      'CONFLICT',
      `Not a revision of this store: ${revision}`,
    );
  return version;
}

/**
 * The port's refusal of a write. A claim the service refuses because shared
 * dashboards show the view carries those boards' titles (`boards`), read
 * from the problem's `boards` extension member; the engine says the refusal
 * around them.
 */
function refusal(failure: Failure): ViewStoreError {
  if (
    failure.problemCode !==
    ViewStoreContract.Errors.REFERENCED_BY_SHARED_DASHBOARD
  )
    return failure.toStoreError();
  return new ViewStoreError('INVALID', failure.message, {
    ...failure.held(),
    boards: boardTitles(failure.problem?.problem.extensions),
  });
}
