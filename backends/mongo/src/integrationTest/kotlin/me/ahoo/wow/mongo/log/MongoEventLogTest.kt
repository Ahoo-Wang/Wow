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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventLogSpec
import me.ahoo.wow.spi.log.EventRecord
import org.junit.jupiter.api.Test

class MongoEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

    @Test
    fun `creating the schema twice is harmless`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        MongoLogFixture.log(4, context).partitions.assert().isEqualTo(4)
    }

    @Test
    fun `a log recorded in another format version is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        MongoLogFixture.bumpFormat("wow_log_" + context.replace('-', '_'))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `a log created by a build without format records is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.createUnrecorded("wow_log_" + context.replace('-', '_'))
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(4, context) }
    }

    @Test
    fun `a payload decimal beyond 34 digits is refused at append, never rounded`(): Unit = runBlocking {
        val valid = stream("a", 1)
        val log = createLog(valid.ref.type.context, 1)
        val tooPrecise = valid.copy(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.2345678901234567890123456789012345}""")),
        )
        assertThrownBy<IllegalArgumentException> { runBlocking { log.append(tooPrecise) } }
        log.head(0).assert().isNull()
        log.read(tooPrecise.ref).toList().assert().isEmpty()
        // An integer of 35 digits has no exact Decimal128 of scale 0 either.
        val tooLarge = tooPrecise.copy(
            events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":12345678901234567890123456789012345}""")),
        )
        assertThrownBy<IllegalArgumentException> { runBlocking { log.append(tooLarge) } }
        log.append(valid).assert().isInstanceOf(AppendOutcome.Committed::class.java)
    }

    @Test
    fun `a different partition count is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(8, context) }
    }
}
