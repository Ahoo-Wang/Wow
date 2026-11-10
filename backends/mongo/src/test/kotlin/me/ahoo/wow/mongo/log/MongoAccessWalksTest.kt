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

package me.ahoo.wow.mongo.log

import me.ahoo.test.asserts.assert
import me.ahoo.wow.spi.access.AccessWalk
import org.bson.Document
import org.junit.jupiter.api.Test

/** A registry document's `access_walk`: a walk read back whole, or from its first fields alone. */
class MongoAccessWalksTest {
    @Test
    fun `a walk reads back as written, and one of its first fields alone reads as just begun`() {
        val heads = mapOf(1 to 4L, 0 to 9L)
        val walk = AccessWalk("region:string", heads, cursor = "0/1:at", walked = 12, completed = true)
        MongoLogAccess.walkOf(MongoLogAccess.documentOf(walk)).assert().isEqualTo(walk)
        val first = Document(MongoEventLog.SCHEMA, "").append(MongoEventLog.HEADS, Document("0", 3))
        MongoLogAccess.walkOf(first).assert().isEqualTo(AccessWalk("", mapOf(0 to 3L)))
    }
}
