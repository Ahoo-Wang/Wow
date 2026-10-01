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

package me.ahoo.wow.viewstore.api

/**
 * The view store's own error codes. The starter registers an HTTP status for each.
 */
object ViewStoreErrorCodes {
    /** The request is not a valid view or preferences write: its config, title, or audience change. HTTP 400. */
    const val VIEW_INVALID = "ViewInvalid"

    /** The request carries no `CoSec-App-Id`. HTTP 400. */
    const val VIEW_APP_REQUIRED = "ViewAppRequired"

    /**
     * A configured system view is read-only: every write to one is refused. A stored system view (owner `(system)`)
     * is written through the view routes, but never shared or claimed. HTTP 403.
     */
    const val SYSTEM_VIEW_READ_ONLY = "SystemViewReadOnly"

    /**
     * An HTTP query of a view store aggregate names no tenant and owner in its path: only the
     * `…/tenant/{tenantId}/owner/{ownerId}/…` query routes are open. Also any view store path whose decoded tenant or
     * owner is empty or holds whitespace or control characters, and a request on stored system views whose path does
     * not spell `…/tenant/(platform)/owner/(system)/…` literally (percent-encoded, a `;` parameter, another letter case).
     * HTTP 400.
     */
    const val VIEW_SCOPE_REQUIRED = "ViewScopeRequired"

    /** The event streams of the view store's aggregates are not open to HTTP queries. HTTP 403. */
    const val VIEW_EVENT_STREAM_CLOSED = "ViewEventStreamClosed"
}
