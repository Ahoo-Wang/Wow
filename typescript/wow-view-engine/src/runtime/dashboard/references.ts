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

import type { DashboardViewConfig } from '../../model/index.js';
import { isViewPanel, type PanelReference } from '../../dashboard/index.js';
import { panelsOf, reasonOf } from './panels.js';

/** Loads what a panel references; rejects when it is gone or unreadable. */
export type PanelResolver = (instanceId: string) => Promise<PanelReference>;

/**
 * The references a dashboard's panels point at, as far as they are known.
 *
 * Three answers per instance id: not asked yet, being loaded, and loaded —
 * where "loaded" is a reference or `null` for one that is gone or unreadable.
 * A fourth, kept apart, is a reference that arrived but could not be put to
 * work (its definition names a source the host does not resolve, say):
 * admission cannot see that, so the reason is remembered here and reported
 * against the panel that asked for it.
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
        .filter(isViewPanel)
        .map(panel => panel.instanceId)
        .filter(id => !this.references.has(id) && !this.pending.has(id)),
    );
    if (wanted.size === 0) return false;

    for (const id of wanted) {
      // A rejection is an answer too: the instance was deleted, or this user
      // may not read it, and only that one panel is affected.
      const loading = this.resolve(id).then(
        reference => this.resolved(id, reference),
        () => this.resolved(id, null),
      );
      this.pending.set(
        id,
        awaited ? loading : loading.catch(error => this.failed(id, error)),
      );
    }
    return true;
  }

  /**
   * Resolves once every reference the current config needs has been loaded or
   * found unreadable. A reference that arrives may add panels of its own to
   * load, so this drains rather than awaiting one round.
   */
  async ready(): Promise<void> {
    while (this.pending.size > 0) await Promise.all([...this.pending.values()]);
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
}
