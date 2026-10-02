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
 * case everything is allowed — bar the system views. A system view is
 * read-only unless the store keeps it (`stored`) and the host grants
 * `editSystem` (D81), whose silence is a refusal; one declared in code is
 * read-only always. That reading is {@link instanceAbilities}, which the
 * view manager's buttons ask as well (D4).
 */

import {
  audienceOf,
  isSystemInstanceId,
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

/**
 * Everything is allowed when a store declares no permissions — everything a
 * silence allows: `editSystem` is not among it (D81).
 */
export const ALLOW_ALL: ViewPermissions = {
  createPersonal: true,
  createShared: true,
  reorder: true,
  setDefault: true,
  instance: () => ({
    save: true,
    rename: true,
    delete: true,
    changeAudience: true,
  }),
};

/** Nothing may be written, which is what a read-only system view answers to all of them. */
const NO_INSTANCE_WRITES: Required<InstancePermissions> = {
  save: false,
  rename: false,
  delete: false,
  changeAudience: false,
};

const INSTANCE_ACTION_SET = {
  save: true,
  rename: true,
  delete: true,
  changeAudience: true,
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
export interface InstanceAbilities extends Required<InstancePermissions> {
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
 * system view read-only whatever that answer is — it lives in code or in
 * the backend's configuration, and no store write can reach it — unless it
 * is one the store keeps (`stored`), its id is not a code view's
 * (`system:`), and the host grants `editSystem` (D81). Then the store's
 * answer applies to it as to a shared view, bar `changeAudience`, which is
 * always false: a system view never moves audience.
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
 *
 * `changeAudience` answers for the move the manager offers — to the other
 * audience — so it asks the store's `changeAudience` (silence allows it, as
 * everywhere) **and** the create permission of the audience the view goes
 * to: sharing puts a view in front of everyone, which is what
 * `createShared` guards, however the view came to exist.
 */
export function instanceAbilities(
  summary: { id: string; scope?: ViewScope; stored?: boolean },
  permissions: ViewPermissions,
): InstanceAbilities {
  if (summary.scope !== undefined && isSystemScope(summary.scope)) {
    if (!editableSystemView(summary, permissions))
      return { ...NO_INSTANCE_WRITES, readOnly: true };
    const { save, rename, delete: remove } = permissions.instance(summary.id);
    // Read-only still when the store grants none of the three: the lock and
    // its sentence read this one field, so they never say "you may change
    // it" over a view nothing can be done to.
    return {
      save,
      rename,
      delete: remove,
      changeAudience: false,
      readOnly: !(save || rename || remove),
    };
  }
  const granted = permissions.instance(summary.id);
  const into =
    summary.scope === undefined
      ? true
      : audienceOf(summary.scope) === 'personal'
        ? permissions.createShared
        : permissions.createPersonal;
  return {
    save: granted.save,
    rename: granted.rename,
    delete: granted.delete,
    changeAudience: granted.changeAudience !== false && into,
    readOnly: false,
  };
}

/**
 * Whether a system view is one this user may write: kept by the store
 * (`stored`), not declared in code (a `system:` id never is, whatever a
 * store says of it), and `editSystem` granted — absent is false (D81).
 */
function editableSystemView(
  summary: { id: string; stored?: boolean },
  permissions: ViewPermissions,
): boolean {
  return (
    summary.stored === true &&
    permissions.editSystem === true &&
    !isSystemInstanceId(summary.id)
  );
}

export class PermissionGuard {
  private readonly store: ViewStore;

  constructor(store: ViewStore) {
    this.store = store;
  }

  /**
   * The store's answer, or everything. A store without `changeAudience` has
   * no such move to permit, so its answer says so for every instance — the
   * one reading the manager's buttons and this guard share (D4).
   */
  of(definitionId: string): ViewPermissions {
    const permissions = this.store.permissions?.(definitionId) ?? ALLOW_ALL;
    if (this.store.changeAudience) return permissions;
    return {
      createPersonal: permissions.createPersonal,
      createShared: permissions.createShared,
      reorder: permissions.reorder,
      setDefault: permissions.setDefault,
      ...(permissions.editSystem === undefined
        ? {}
        : { editSystem: permissions.editSystem }),
      instance: id => ({
        ...permissions.instance(id),
        changeAudience: false,
      }),
    };
  }

  /** A system view is created only where `editSystem` is granted (D81). */
  requireCreate(definitionId: string, scope: ViewScope): void {
    this.require(
      mayCreate(this.of(definitionId), scope),
      'view.create.forbidden',
    );
  }

  /**
   * Asks for identity, scope and whether the store keeps it, so an instance
   * answers as well as a summary.
   */
  requireInstance(
    instance: Pick<
      ViewInstanceSummary,
      'id' | 'definitionId' | 'scope' | 'stored'
    >,
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

/**
 * Whether a view may be created in `scope`: the audience's create
 * permission, and for a system view `editSystem`, whose silence refuses.
 */
export function mayCreate(
  permissions: ViewPermissions,
  scope: ViewScope,
): boolean {
  if (isSystemScope(scope)) return permissions.editSystem === true;
  return audienceOf(scope) === 'shared'
    ? permissions.createShared
    : permissions.createPersonal;
}
