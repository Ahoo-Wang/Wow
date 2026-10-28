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
 * The reserved owner of shared views and shared preferences
 * (`ViewStoreContext.SHARED_OWNER_ID` on the server). The parentheses keep it
 * apart from every user id. A request acts as it by naming it in `Wow-Scope`
 * (`SHARED_SCOPE`), as far as the service lets the caller.
 */
export const SHARED_OWNER_ID = '(shared)';

/**
 * The reserved owner of stored system views
 * (`ViewStoreContext.SYSTEM_OWNER_ID`), under {@link SYSTEM_TENANT_ID} only.
 */
export const SYSTEM_OWNER_ID = '(system)';

/**
 * The one tenant stored system views live under
 * (`ViewStoreContext.SYSTEM_TENANT_ID`): CoSec's platform tenant, by value,
 * not the default tenant `(0)`. System views are global, so they are read
 * and written in this tenant whatever the caller's own (`SYSTEM_SCOPE`).
 */
export const SYSTEM_TENANT_ID = '(platform)';

/**
 * Which audience a view is read or written as: `personal` names no scope,
 * and the service takes the owner from the caller; `shared` and `system`
 * name the reserved owner (and, for `system`, tenant) in `Wow-Scope`
 * (`scopeAt`).
 */
export type Place = 'personal' | 'shared' | 'system';
