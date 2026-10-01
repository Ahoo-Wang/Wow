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
  type DashboardViewConfig,
  type Issue,
} from '../../model/index.js';
import {
  panelsOf,
  referencedInstance,
  type PanelReference,
} from '../../dashboard/index.js';
import { panelFailure, reasonOf } from './panels.js';
import { isViewCommandError } from '../write.js';

/** Loads what a panel references; rejects when it is gone or unreadable. */
export type PanelResolver = (instanceId: string) => Promise<PanelReference>;

/**
 * The references a dashboard's panels point at, as far as they are known.
 *
 * Three answers per instance id: not asked yet, being loaded, and loaded —
 * where "loaded" is a reference or `null` for one that is gone or unreadable.
 * A fourth, kept apart, is a load that failed: a reference that arrived but
 * could not be put to work (its definition names a source the host does not
 * resolve, say), or a read that failed for a reason that may pass — the
 * store unreachable, or answering with an error of its own. Admission cannot
 * see either, so the reason is remembered here and reported against the
 * panel that asked for it (`dashboard.panel.failed`), and `retry` asks again.
 * Only a store's `NOT_FOUND` or `FORBIDDEN` — or another answer that would be
 * the same the next time — says the view is gone (`dashboard.panel.unavailable`).
 *
 * The runtime is told once per settled load — resolved, unreadable, or
 * failed — and re-judges its draft then; what it does with that is its own.
 */
export class PanelReferences {
  /** Resolved references by instance id; `null` once known to be unreadable. */
  private readonly references = new Map<string, PanelReference | null>();
  /** Why a resolved reference could not be brought into service, by id. */
  private readonly failures = new Map<string, string>();
  private readonly pending = new Map<string, Promise<void>>();

  constructor(
    private readonly resolve: PanelResolver,
    /** Called after each load settles, however it settled. */
    private readonly settled: () => void,
  ) {}

  /** What admission validates the panels against. */
  get known(): ReadonlyMap<string, PanelReference | null> {
    return this.references;
  }

  /** True while a reference is still being loaded. */
  get resolving(): boolean {
    return this.pending.size > 0;
  }

  /** The reference for an id, `null` for an unreadable one, `undefined` for one not loaded yet. */
  get(instanceId: string): PanelReference | null | undefined {
    return this.references.get(instanceId);
  }

  /** Why the reference could not be put to work, if it could not. */
  failure(instanceId: string): string | undefined {
    return this.failures.get(instanceId);
  }

  /**
   * Starts loading the references a config needs and does not have yet, and
   * says whether it started any.
   *
   * `awaited` says whether anyone is waiting on the outcome. `ready()` waits
   * on what the constructor starts, so a load that cannot be brought into
   * service at all refuses the opening. A load `edit` or `adoptSaved` starts
   * has no such caller: letting it reject would leave an unhandled rejection
   * and no trace on screen, so its failure becomes this panel's issue.
   */
  load(config: DashboardViewConfig, awaited: boolean): boolean {
    const wanted = new Set(
      panelsOf(config)
        .map(referencedInstance)
        .filter(
          (id): id is string =>
            id !== undefined &&
            !this.references.has(id) &&
            !this.pending.has(id),
        ),
    );
    if (wanted.size === 0) return false;
    for (const id of wanted) void this.start(id, awaited);
    return true;
  }

  /**
   * Loads one saved view before any panel points at it — the one a board is
   * about to add, so the panel starts at the size of what it shows — and
   * resolves once it has settled, however it settled; `null` when it is
   * already known or on its way, and there is nothing to start.
   */
  fetch(instanceId: string): Promise<void> | null {
    if (this.references.has(instanceId)) return null;
    const pending = this.pending.get(instanceId);
    if (pending) return pending.catch(() => undefined);
    return this.start(instanceId, false);
  }

  /**
   * Asks again for every reference whose load failed (`failure`), and says
   * whether there was one: a board's refresh, which a reader presses
   * because something on it looks wrong.
   */
  retryFailed(): boolean {
    const failed = [...this.failures.keys()];
    for (const id of failed) this.retry(id);
    return failed.length > 0;
  }

  /**
   * Asks again for one reference whose load failed, forgetting the failure
   * first; `false` when it had not failed, and there is nothing to ask.
   */
  retry(instanceId: string | undefined): boolean {
    if (
      instanceId === undefined ||
      !this.failures.has(instanceId) ||
      this.pending.has(instanceId)
    )
      return false;
    this.failures.delete(instanceId);
    this.references.delete(instanceId);
    void this.start(instanceId, false);
    return true;
  }

  /**
   * A finding of admission as the reader should hear it: a view the kernel
   * finds missing (`dashboard.panel.unavailable`) because its read failed —
   * not because the store said it is gone — is that failure
   * (`dashboard.panel.failed`). A reader told the view was deleted, or not
   * shared, would go and ask its owner for nothing.
   */
  restate(found: Issue): Issue {
    const [, index] = found.path;
    const instance = found.params?.instance;
    if (
      found.code !== 'dashboard.panel.unavailable' ||
      typeof index !== 'number' ||
      typeof instance !== 'string'
    )
      return found;
    const failure = this.failures.get(instance);
    return failure === undefined
      ? found
      : panelFailure(index, instance, failure);
  }

  private start(id: string, awaited: boolean): Promise<void> {
    // A rejection is an answer too. The instance was deleted, or this user
    // may not read it: only that one panel is affected, and asking again
    // would answer the same. Anything else may pass, and is a failure the
    // panel offers to try again.
    const loading = this.resolve(id).then(
      reference => this.resolved(id, reference),
      (error: unknown) =>
        gone(error)
          ? this.resolved(id, null)
          : this.unreadable(id, reasonOf(error)),
    );
    const settled = awaited
      ? loading
      : loading.catch(error => this.failed(id, error));
    this.pending.set(id, settled);
    return settled;
  }

  /**
   * Resolves once every reference the current config needs has been loaded or
   * found unreadable. A reference that arrives may add panels of its own to
   * load, so this drains rather than awaiting one round.
   */
  async ready(): Promise<void> {
    while (this.pending.size > 0) await Promise.all([...this.pending.values()]);
  }

  /**
   * A reference known without asking the store — the view a board has just
   * saved one of its own views as — so the panel pointing at it does not
   * blank while it is read back.
   */
  seed(reference: PanelReference): void {
    this.references.set(reference.instance.id, reference);
    this.failures.delete(reference.instance.id);
  }

  private resolved(id: string, reference: PanelReference | null): void {
    this.pending.delete(id);
    this.references.set(id, reference);
    this.settled();
  }

  private failed(id: string, error: unknown): void {
    this.failures.set(id, reasonOf(error));
    this.settled();
  }

  /** A read that failed for a reason that may pass; see `gone`. */
  private unreadable(id: string, reason: string): void {
    this.pending.delete(id);
    this.references.set(id, null);
    this.failures.set(id, reason);
    this.settled();
  }
}

/**
 * Whether a failed read says the view is not there for this reader — the
 * store's `NOT_FOUND` or `FORBIDDEN`, or another answer it would give again
 * (`INVALID`, `UNSUPPORTED`), or the engine's own refusal (a definition this
 * release does not declare) — rather than that it could not be read just
 * now: the store `UNAVAILABLE`, or a failure nobody named.
 */
function gone(error: unknown): boolean {
  if (isViewStoreError(error)) return error.code !== 'UNAVAILABLE';
  return isViewCommandError(error);
}
