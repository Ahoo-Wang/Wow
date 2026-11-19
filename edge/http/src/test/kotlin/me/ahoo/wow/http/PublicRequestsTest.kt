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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.DevelopmentHeaders
import me.ahoo.wow.api.wire.WowHeaders
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The `Wow-` headers the public port takes ([PublicRequests.KNOWN]) are held to the constants Wow's clients send them
 * by: a header added to [WowHeaders] or [DevelopmentHeaders] fails here until it is put on one side, so the port never
 * refuses Wow's own clients for a header nobody listed.
 */
class PublicRequestsTest {
    /** The `Wow-` constants that are no request header of the public port, each with why. */
    private val notTaken = mapOf(
        WowHeaders.CALL to "a service's call, taken on the internal port alone (403 on the public one)",
        WowHeaders.REBUILDING to "a response's header, on a snapshot query's answer",
    )

    /** The `Wow-` values of the string constants of [holder]. */
    private fun wowConstants(holder: Class<*>): Set<String> = holder.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.apply { isAccessible = true }.get(null) as String }
        .filter { it.startsWith(PublicRequests.PREFIX) }
        .toSet()

    @Test
    fun `every Wow constant of the headers is taken by the public port or named here as not a request header`() {
        val constants = wowConstants(WowHeaders::class.java) + wowConstants(DevelopmentHeaders::class.java)
        constants.assert().contains(WowHeaders.REQUEST_ID, DevelopmentHeaders.USER)
        (constants - notTaken.keys).assert().containsExactlyInAnyOrderElementsOf(PublicRequests.KNOWN)
        PublicRequests.KNOWN.assert().doesNotContainAnyElementsOf(notTaken.keys)
    }
}
