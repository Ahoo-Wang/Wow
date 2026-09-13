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

import { cloneSnapshot } from '../lib/types.js';
import type {
  ViewSession,
  ViewInstance,
  ViewInstancePermissions,
} from '../contracts/viewModel.js';
import type { ViewHost } from '../contracts/ViewHost.js';
import { isSystemSession } from './sessionState.js';

export const deniedPermissions = Object.freeze({
  save: false,
  saveAsPersonal: false,
  saveAsShared: false,
  delete: false,
  rename: false,
});

/** Policy outages deny UI capabilities without interrupting rendering. */
export function definitionPermissionsFor(host: ViewHost) {
  try {
    return host.permission?.getDefinition?.();
  } catch {
    return undefined;
  }
}

export function permissionsFor(
  host: ViewHost,
  session?: ViewSession,
): ViewInstancePermissions {
  if (session?.kind === 'dashboard' && !session.persisted) {
    const grants = definitionPermissionsFor(host);
    const available = typeof host.instance?.create === 'function';
    return {
      ...deniedPermissions,
      save:
        available &&
        (session.instance.scope.type === 'personal'
          ? grants?.createPersonal === true
          : grants?.createShared === true),
      saveAsPersonal: available && grants?.createPersonal === true,
      saveAsShared: available && grants?.createShared === true,
    };
  }
  if (
    !session ||
    session.positionId !== session.instance.id ||
    !host.permission?.getInstance
  )
    return deniedPermissions;
  try {
    const permissions = host.permission?.getInstance(
      cloneSnapshot<ViewInstance>(session.instance),
    );
    const system = isSystemSession(session);
    return {
      delete:
        !system &&
        typeof host.instance?.delete === 'function' &&
        permissions?.delete === true,
      rename:
        !system &&
        typeof host.instance?.rename === 'function' &&
        permissions?.rename === true,
      save:
        typeof host.instance?.save === 'function' && permissions?.save === true,
      saveAsPersonal:
        typeof host.instance?.create === 'function' &&
        permissions?.saveAsPersonal === true,
      saveAsShared:
        typeof host.instance?.create === 'function' &&
        permissions?.saveAsShared === true,
    };
  } catch {
    return deniedPermissions;
  }
}
