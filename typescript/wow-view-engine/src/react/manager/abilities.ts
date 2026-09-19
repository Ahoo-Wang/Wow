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

import { isSystemScope, type ViewInstanceSummary } from '../../model/index.js';
import type { ViewPermissions } from '../../store/ViewStore.js';

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

const NO_INSTANCE_WRITES: ManagedInstanceAbilities = {
  rename: false,
  delete: false,
};

export function abilitiesOf(
  items: readonly ViewInstanceSummary[],
  permissions: ViewPermissions,
): ViewManagerAbilities {
  const instance = (id: string): ManagedInstanceAbilities => {
    // A system view ships with the definition, so no store write reaches it
    // whatever the permissions answer for its id.
    const summary = items.find(item => item.id === id);
    if (summary && isSystemScope(summary.scope)) return NO_INSTANCE_WRITES;
    const granted = permissions.instance(id);
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
