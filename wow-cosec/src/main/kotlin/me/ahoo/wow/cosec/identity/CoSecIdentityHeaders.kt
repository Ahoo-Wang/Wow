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

package me.ahoo.wow.cosec.identity

import me.ahoo.wow.webflux.route.identity.IdentityHeaderAliases

/**
 * CoSec's request headers that Wow reads as identity: `CoSec-Request-Id` after `Command-Request-Id`, and, for a
 * spaced aggregate only, `CoSec-Space-Id` after `Wow-Space-Id`, on commands and queries alike. Both are declared by
 * the client (CoSec's `spaceIdProvider`, say), not vouched for. Since 9.3.0.
 */
object CoSecIdentityHeaders {
    const val REQUEST_ID = "CoSec-Request-Id"
    const val SPACE_ID = "CoSec-Space-Id"

    /** The aliases CoSec contributes to every route. */
    @JvmField
    val ALIASES = IdentityHeaderAliases(spaceId = listOf(SPACE_ID), requestId = listOf(REQUEST_ID))
}
