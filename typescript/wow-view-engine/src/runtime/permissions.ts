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
 * says: it lives in code, and no store write can reach it.
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
    if (isSystemScope(instance.scope))
      throw new ViewCommandError(
        issue('view.system.read-only', [], { action }),
      );
    this.require(
      this.of(instance.definitionId).instance(instance.id)[action],
      `view.${action}.forbidden`,
    );
  }

  require(allowed: boolean, code: string): void {
    if (!allowed) throw new ViewCommandError(issue(code, []));
  }
}
