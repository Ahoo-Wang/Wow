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

package me.ahoo.wow.webflux.route.identity

/**
 * Further request headers the HTTP adapter reads for the space and the request ID, after Wow's own `Wow-Space-Id`
 * and `Command-Request-Id`, in order. An integration (CoSec, say) contributes its headers here once instead of
 * overriding the command builder extractor and the query request scope. Since 9.3.0.
 *
 * An alias is a header like any other: the value is declared by the client, not vouched for. The space aliases apply,
 * as `Wow-Space-Id` does, only to a spaced aggregate. A blank header is no value.
 *
 * @property spaceId headers read, in order, when `Wow-Space-Id` gives no space.
 * @property requestId headers read, in order, when `Command-Request-Id` gives no request ID.
 */
data class IdentityHeaderAliases(
    val spaceId: List<String> = emptyList(),
    val requestId: List<String> = emptyList(),
) {
    operator fun plus(other: IdentityHeaderAliases): IdentityHeaderAliases = IdentityHeaderAliases(
        spaceId = (spaceId + other.spaceId).distinct(),
        requestId = (requestId + other.requestId).distinct(),
    )

    companion object {
        @JvmField
        val NONE = IdentityHeaderAliases()

        /** All of [aliases], in order, as one. */
        fun merge(aliases: Iterable<IdentityHeaderAliases>): IdentityHeaderAliases =
            aliases.fold(NONE, IdentityHeaderAliases::plus)
    }
}
