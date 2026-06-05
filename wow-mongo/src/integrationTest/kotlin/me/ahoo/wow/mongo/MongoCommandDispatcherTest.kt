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

import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.metrics.MetricEventStore
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.tck.container.MongoTestFixture
import me.ahoo.wow.tck.modeling.command.CommandDispatcherSpec
import org.junit.jupiter.api.extension.RegisterExtension

class MongoCommandDispatcherTest : CommandDispatcherSpec() {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    override fun createSnapshotRepository(): SnapshotRepository {
        val database = mongo.database()
        SnapshotSchemaInitializer(database).initSchema(aggregateMetadata)
        return MongoSnapshotRepository(database)
    }

    override fun createEventStore(): EventStore {
        val database = mongo.database()
        EventStreamSchemaInitializer(database).initSchema(aggregateMetadata.namedAggregate)
        return MetricEventStore(MongoEventStore(database)).metrizable()
    }
}
