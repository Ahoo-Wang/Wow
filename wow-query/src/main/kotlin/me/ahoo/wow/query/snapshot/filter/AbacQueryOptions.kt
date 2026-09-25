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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.wow.exception.ErrorCodes
import me.ahoo.wow.exception.WowException

/**
 * The optional tightenings of [AbacQueryPolicy]. The defaults keep its permissive semantics.
 *
 * @property requirePrincipalTags rejects an HTTP snapshot query whose principal has no ABAC tags, instead of letting
 * it match every resource. In-process queries are trusted and never rejected for this.
 * @property matchMissingTagKey lets a resource that lacks one of the principal's tag keys (or has it empty) match, as
 * a public resource. Off, a resource matches only when it carries every non-wildcard key with a value the principal
 * holds.
 */
data class AbacQueryOptions(
    val requirePrincipalTags: Boolean = false,
    val matchMissingTagKey: Boolean = true,
) {
    companion object {
        val DEFAULT = AbacQueryOptions()
    }
}

/** An HTTP snapshot query rejected because its principal has no ABAC tags; see [AbacQueryOptions.requirePrincipalTags]. */
class AbacPrincipalTagsRequiredException : WowException(
    ErrorCodes.ILLEGAL_ACCESS_QUERY_SCOPE,
    "Snapshot query requires the principal's ABAC tags.",
)
