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

package me.ahoo.wow.postgres

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.access.AccessWalk
import org.junit.jupiter.api.Test

/** The registry's `access_walk` column: a walk as one JSON object, read back whole or from its first fields alone. */
class PostgresAccessWalksTest {
    @Test
    fun `a walk reads back as written, its cursor null too, and one of its first fields alone reads as just begun`() {
        val walk = AccessWalk("region:string", mapOf(1 to 4L, 0 to 9L), cursor = "1:at", walked = 12, completed = true)
        PostgresAccessWalks.walkOf(PostgresAccessWalks.textOf(walk)).assert().isEqualTo(walk)
        val begun = AccessWalk("region:string", mapOf(0 to 0L))
        PostgresAccessWalks.walkOf(PostgresAccessWalks.textOf(begun)).assert().isEqualTo(begun)
        val first = """{"schema":"","heads":{"0":3}}"""
        PostgresAccessWalks.walkOf(first).assert().isEqualTo(AccessWalk("", mapOf(0 to 3L)))
    }
}
