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
 * What a command is allowed to do, asked before it is dispatched.
 *
 * The store answers synchronously and may answer nothing at all, in which
 * case everything is allowed. A system view is read-only whatever the store
 * says: it lives in code, and no store write can reach it — and that reading
 * is {@link instanceAbilities}, which the view manager's buttons ask as well
 * (D4).
 */

import {
  audienceOf,
  isSystemScope,
  type ViewInstanceSummary,
  type ViewScope,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import type {
  InstancePermissions,
  ViewPermissions,
  ViewStore,
} from '../store/ViewStore.js';
import { ViewCommandError } from './write.js';

/** Everything is allowed when a store declares no permissions. */
export const ALLOW_ALL: ViewPermissions = {
  createPersonal: true,
  createShared: true,
  reorder: true,
  setDefault: true,
  instance: () => ({ save: true, rename: true, delete: true }),
};

/** Nothing may be written, which is what a system view answers to all three. */
const NO_INSTANCE_WRITES: InstancePermissions = {
  save: false,
  rename: false,
  delete: false,
};

const INSTANCE_ACTION_SET = {
  save: true,
  rename: true,
  delete: true,
} satisfies Record<keyof InstancePermissions, true>;

/**
 * Every action an instance permission answers for. The `satisfies` above is
 * what makes it *every* one: a fourth member of `InstancePermissions` does
 * not compile until it is named here, and `test/messages.test.tsx` expands
 * this set into the `view.<action>.forbidden` codes the catalogue owes a
 * sentence to.
 */
export const INSTANCE_ACTIONS = Object.keys(
  INSTANCE_ACTION_SET,
) as readonly (keyof InstancePermissions)[];

/** What may be done to one instance, and — when nothing may — why not. */
export interface InstanceAbilities extends InstancePermissions {
  /**
   * True when the refusal is the view's own nature rather than the store's
   * answer. The guard words that refusal differently
   * (`view.system.read-only`), and it is the one thing a caller cannot fix
   * by being granted more.
   */
  readOnly: boolean;
}

/**
 * What may be done to one instance: the store's answer for its id, with a
 * system view read-only whatever that answer is — it lives in code, and no
 * store write can reach it.
 *
 * **One reading, for both callers.** D4 has a manager's button exist exactly
 * where the command would be allowed, so the guard and the manager asking
 * separately is two answers to one question: the two spelled the system
 * check out apart from each other, and the pair is only ever one edit away
 * from offering a Rename the guard then refuses.
 *
 * A scope that is not to hand is not a system view: an id the manager's rows
 * do not hold gets the store's answer, and the guard — which always has the
 * scope, because it reads a summary or an instance — is the one that refuses
 * it for certain.
 */
export function instanceAbilities(
  summary: { id: string; scope?: ViewScope },
  permissions: ViewPermissions,
): InstanceAbilities {
  if (summary.scope !== undefined && isSystemScope(summary.scope))
    return { ...NO_INSTANCE_WRITES, readOnly: true };
  const granted = permissions.instance(summary.id);
  return {
    save: granted.save,
    rename: granted.rename,
    delete: granted.delete,
    readOnly: false,
  };
}

export class PermissionGuard {
  private readonly store: ViewStore;

  constructor(store: ViewStore) {
    this.store = store;
  }

  of(definitionId: string): ViewPermissions {
    return this.store.permissions?.(definitionId) ?? ALLOW_ALL;
  }

  requireCreate(definitionId: string, scope: ViewScope): void {
    const permissions = this.of(definitionId);
    this.require(
      audienceOf(scope) === 'shared'
        ? permissions.createShared
        : permissions.createPersonal,
      'view.create.forbidden',
    );
  }

  /** Asks for identity and scope alone, so an instance answers as well as a summary. */
  requireInstance(
    instance: Pick<ViewInstanceSummary, 'id' | 'definitionId' | 'scope'>,
    action: keyof InstancePermissions,
  ): void {
    const abilities = instanceAbilities(
      instance,
      this.of(instance.definitionId),
    );
    if (abilities[action]) return;
    // Two refusals, one decision: `instanceAbilities` says whether the
    // command may run, and `readOnly` says which of the two sentences the
    // user gets — a built-in view nobody can change, or a permission the
    // store withheld.
    throw new ViewCommandError(
      abilities.readOnly
        ? issue('view.system.read-only', [], { action })
        : issue(`view.${action}.forbidden`, []),
    );
  }

  require(allowed: boolean, code: string): void {
    if (!allowed) throw new ViewCommandError(issue(code, []));
  }
}
