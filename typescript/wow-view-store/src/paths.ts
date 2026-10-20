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
 * (`ViewStoreService.SHARED_OWNER_ID` on the server). The parentheses keep it
 * apart from every user id, so a path whose owner segment is `(shared)` is the
 * shared audience's.
 *
 * A host nobody signs in to fills `{ownerId}` with it by default (its own
 * request interceptor), and then has shared views and shared preferences
 * only.
 */
export const SHARED_OWNER_ID = '(shared)';

/**
 * The reserved owner of stored system views
 * (`ViewStoreService.SYSTEM_OWNER_ID` on the server).
 */
export const SYSTEM_OWNER_ID = '(system)';

/**
 * The one tenant stored system views live under
 * (`ViewStoreService.SYSTEM_TENANT_ID`): the value of CoSec's platform
 * tenant, not the default tenant `(0)`. System views are global, so the
 * store writes them on `tenant/(platform)/owner/(system)` whatever the
 * caller's own tenant, and the security gateway decides who may, by that
 * path.
 */
export const SYSTEM_TENANT_ID = '(platform)';

/**
 * Where a view lives on the server: the owner segment of its path. `personal`
 * leaves `{ownerId}` to the fetcher's interceptors (fetcher-cosec's resource
 * attribution fills it from the token's `sub`); `shared` names
 * {@link SHARED_OWNER_ID}, which the interceptors never replace; `system`
 * names both the tenant and the owner of stored system views,
 * {@link SYSTEM_TENANT_ID} and {@link SYSTEM_OWNER_ID}.
 */
export type Place = 'personal' | 'shared' | 'system';
