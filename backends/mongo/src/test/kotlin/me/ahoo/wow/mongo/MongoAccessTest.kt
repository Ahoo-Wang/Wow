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

package me.ahoo.wow.mongo

import me.ahoo.test.asserts.assert
import org.bson.Document
import org.junit.jupiter.api.Test

/** How a log stream stores its access attributes on MongoDB (D420). */
class MongoAccessTest {
    @Test
    fun `a tenant-scoped key tells every tenant and value apart, whatever characters they hold`() {
        // A tenant id and a value are any non-blank text: no separator character could tell where the tenant ends.
        val pairs = listOf(
            "a" to "b:c",
            "a:b" to "c",
            "a\u0000" to "b",
            "a" to "\u0000b",
            "1:a" to "b",
            "1" to ":ab",
            "ab" to "c",
            "a" to "bc",
        )
        pairs.map { (tenant, value) -> MongoAccess.key(tenant, value) }.distinct().size.assert().isEqualTo(pairs.size)
        MongoAccess.key("t3", "u-7").assert().isEqualTo("2:t3u-7")
    }

    @Test
    fun `a stream leaves a built-in attribute without a value out, keys the others by tenant, and reads them back`() {
        val access = linkedMapOf<String, Any?>("owner" to "alice", "space" to null, "region" to null, "teams" to listOf("a"))
        val stored = MongoAccess.log(access)
        stored.keys.assert().containsExactly("owner", "region", "teams")
        MongoAccess.keys("acme", access).assert().isEqualTo(Document("owner", MongoAccess.key("acme", "alice")))
        MongoAccess.keys("acme", mapOf("owner" to null, "space" to null)).isEmpty().assert().isTrue()
        MongoAccess.ofLog(stored).assert().isEqualTo(access)
        MongoAccess.ofLog(stored)!!.keys.assert().startsWith("owner", "space")
        // One written before D420, with the nulls, reads the same.
        MongoAccess.ofLog(Document(access)).assert().isEqualTo(access)
    }
}
