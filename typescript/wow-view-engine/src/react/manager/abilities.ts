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
 * Which of a view manager's buttons exist at all, read from the permissions
 * the store handed out and from the rows actually on screen.
 */

import type { ViewInstanceSummary } from '../../model/index.js';
import type { ViewPermissions } from '../../store/ViewStore.js';
import { instanceAbilities } from '../../runtime/permissions.js';

/** What may be done to one row. `save` is not among them: nothing here edits a config. */
export interface ManagedInstanceAbilities {
  rename: boolean;
  delete: boolean;
}

export interface ViewManagerAbilities {
  reorder: boolean;
  setDefault: boolean;
  instance(id: string): ManagedInstanceAbilities;
  /**
   * Whether anything at all can be managed: the order, the default, or the
   * title or existence of any row on the list. False means the manager would
   * open on a dialog of read-only rows, so the way in is not offered — a
   * button whose only lesson is that it leads nowhere.
   */
  anything: boolean;
}

export function abilitiesOf(
  items: readonly ViewInstanceSummary[],
  permissions: ViewPermissions,
): ViewManagerAbilities {
  const instance = (id: string): ManagedInstanceAbilities => {
    // Asked of `instanceAbilities`, which is where "a system view ships with
    // the definition, so no store write reaches it" is decided — the same
    // reading the permission guard refuses by, so a button exists exactly
    // where the command would be allowed (D4). Deciding it here as well is
    // how a Rename came to be offered on a row the guard then refused.
    const granted = instanceAbilities(
      { id, scope: items.find(item => item.id === id)?.scope },
      permissions,
    );
    return { rename: granted.rename, delete: granted.delete };
  };
  return {
    reorder: permissions.reorder,
    setDefault: permissions.setDefault,
    instance,
    // Asked of the rows on screen rather than of the permissions alone: a
    // list whose every row is a system view answers "nothing", however freely
    // the store hands out `rename` and `delete`.
    anything:
      permissions.reorder ||
      permissions.setDefault ||
      items.some(item => {
        const granted = instance(item.id);
        return granted.rename || granted.delete;
      }),
  };
}
