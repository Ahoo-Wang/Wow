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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class MongoTopologyTest {
    @Test
    fun `a server is standalone unless it names its replica set or is a mongos`() {
        MongoTopology.standalone(Document("isWritablePrimary", true)).assert().isTrue()
        MongoTopology.standalone(Document("setName", "rs0")).assert().isFalse()
        MongoTopology.standalone(Document("msg", "isdbgrid")).assert().isFalse()
    }

    @Test
    fun `a replica set member lists the set's arbiters, a mongos or a standalone mongod none`() {
        MongoTopology.arbiters(Document("setName", "rs0").append("arbiters", listOf("db-3:27017")))
            .assert().containsExactly("db-3:27017")
        MongoTopology.arbiters(Document("setName", "rs0").append("hosts", listOf("db-1:27017"))).assert().isEmpty()
        MongoTopology.arbiters(Document("msg", "isdbgrid")).assert().isEmpty()
    }

    @Test
    fun `an arbiter is warned of with what it costs and what to do, none without one`() {
        val warning = MongoTopology.arbiterWarning(listOf("db-3:27017"), "wow.mongo")
        warning.assert().contains(
            "The client of wow.mongo connects to a replica set with an arbiter (db-3:27017)",
            "cannot acknowledge a majority write",
            "Replace the arbiter with a third data-bearing member",
        )
        MongoTopology.arbiterWarning(emptyList(), "wow.mongo").assert().isNull()
    }

    @Test
    fun `a replica set with an arbiter is warned of once a call, one without none`() {
        val logger = LoggerFactory.getLogger(MongoTopology::class.java) as Logger
        val warnings = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(warnings)
        try {
            val withArbiter = Document("setName", "rs0").append("arbiters", listOf("db-3:27017"))
            MongoTopology.warnOfArbiters(withArbiter, "wow.mongo")
            MongoTopology.warnOfArbiters(Document("setName", "rs0"), "wow.mongo")
            warnings.list.map { it.level to it.formattedMessage }.assert().containsExactly(
                Level.WARN to MongoTopology.arbiterWarning(listOf("db-3:27017"), "wow.mongo"),
            )
        } finally {
            logger.detachAppender(warnings)
        }
    }

    @Test
    fun `a standalone mongod is refused, a replica set member taken`() {
        assertThrows<IllegalStateException> { MongoTopology.requireReplicated(Document(), "wow.mongo") }
        MongoTopology.requireReplicated(Document("setName", "rs0"), "wow.mongo")
    }
}
