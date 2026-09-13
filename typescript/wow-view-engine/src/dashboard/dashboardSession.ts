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

import type {
  DashboardSession,
  DashboardViewInstance,
} from './dashboardModel.js';
import type { DeepReadonly } from '../lib/types.js';
import { validateDashboardConfig } from './dashboardValidation.js';
import { message } from '../lib/snapshot.js';
export function createDashboardSession(
  instance: DeepReadonly<DashboardViewInstance>,
  maxConfigBytes?: number,
): DashboardSession {
  return deriveDashboardSession(
    {
      kind: 'dashboard',
      persisted: true,
      positionId: instance.id,
      editorEpoch: 0,
      editorValidity: {},
      editVersion: 0,
      validation: [],
      baseline: instance,
      instance,
      dirty: false,
      writeStatus: 'idle',
      writeError: null,
      requiresReload: false,
    },
    0,
    false,
    maxConfigBytes,
  );
}
export function deriveDashboardSession(
  session: DashboardSession,
  editVersion: number,
  dirty: boolean,
  maxConfigBytes?: number,
  limits?: { maxPanels?: number; maxFilters?: number },
): DashboardSession {
  let validation: DashboardSession['validation'] = [];
  try {
    validateDashboardConfig(
      session.instance.config,
      true,
      maxConfigBytes,
      limits,
    );
  } catch (error) {
    validation = [{ id: 'dashboard', message: message(error) }];
  }
  const invalidEditors = Object.entries(session.editorValidity)
    .filter(([, valid]) => !valid)
    .map(([id]) => ({ id, message: '编辑输入无效' }));
  validation = [...validation, ...invalidEditors];
  return {
    ...session,
    editVersion,
    dirty: !session.persisted || dirty || invalidEditors.length > 0,
    validation,
    ...(session.conflict
      ? {
          conflict: {
            ...session.conflict,
            editVersion,
            local: session.instance,
          },
        }
      : {}),
  };
}
