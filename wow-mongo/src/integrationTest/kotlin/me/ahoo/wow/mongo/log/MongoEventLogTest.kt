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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventLogSpec
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
    fun `a different partition count is refused`() {
        val context = MongoLogFixture.newContext()
        MongoLogFixture.log(4, context)
        assertThrownBy<IllegalStateException> { MongoLogFixture.log(8, context) }
    }
}
