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

import com.mongodb.client.model.Filters
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.mongo.MongoNames
import org.junit.jupiter.api.Test

/** The reads of a partition: its head, against the partition's counter. */
class MongoTailReadsTest {
    @Test
    fun `head refuses a partition without its counter, as lineage does`(): Unit = runBlocking {
        val context = MongoLogFixture.newContext()
        val log = MongoLogFixture.log(2, context)
        MongoLogFixture.client.getDatabase(MongoLogFixture.DATABASE)
            .getCollection(MongoNames.of("wow_log", context, "partition"))
            .deleteOne(Filters.eq("_id", 1))
        // A damaged store: the partition is not empty, it has no counter, and both reads say so.
        val missing = "The log of $context has no counter of partition 1."
        assertThrownBy<IllegalStateException> { runBlocking { log.head(1) } }.hasMessage(missing)
        assertThrownBy<IllegalStateException> { runBlocking { log.lineage(1) } }.hasMessage(missing)
    }
}
