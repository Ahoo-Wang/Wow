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
 * Where a view lives on the server: the owner segment of its path. `personal`
 * leaves `{ownerId}` to the fetcher's interceptors (fetcher-cosec's resource
 * attribution fills it from the token's `sub`); `shared` names
 * {@link SHARED_OWNER_ID}, which the interceptors never replace.
 */
export type Place = 'personal' | 'shared';

/** Every route starts here; the tenant and owner are path variables. */
const SCOPE = '/view-store/tenant/{tenantId}/owner/{ownerId}';

/** The view store's routes, relative to the fetcher's base URL. */
export const PATHS = {
  /** `POST`: create (the server generates the id). */
  views: `${SCOPE}/view`,
  /** `DELETE`: Wow's delete of the aggregate. */
  view: `${SCOPE}/view/{id}`,
  save: `${SCOPE}/view/{id}/save`,
  rename: `${SCOPE}/view/{id}/rename`,
  /** Sent to the view's personal path; moves it to `(shared)`. */
  share: `${SCOPE}/view/{id}/share`,
  /** Sent to the caller's own path; moves a shared view to the caller. */
  claim: `${SCOPE}/view/{id}/claim`,
  single: `${SCOPE}/view/snapshot/single`,
  list: `${SCOPE}/view/snapshot/list`,
  /** The view as the write with this request id left it; `204` for a delete. */
  replay: `${SCOPE}/view/requests/{requestId}`,
  /** Served under `(shared)` only. */
  systemViews: `${SCOPE}/system-views`,
  systemView: `${SCOPE}/system-views/{id}`,
  preferences: `${SCOPE}/definitions/{definitionId}/preferences`,
} as const;

/**
 * The path variables of a request at `place`. The tenant is always the
 * interceptors'; the owner is theirs for a personal path.
 */
export function pathAt(
  place: Place,
  variables: Record<string, string> = {},
): Record<string, string> {
  return place === 'shared'
    ? { ...variables, ownerId: SHARED_OWNER_ID }
    : { ...variables };
}
