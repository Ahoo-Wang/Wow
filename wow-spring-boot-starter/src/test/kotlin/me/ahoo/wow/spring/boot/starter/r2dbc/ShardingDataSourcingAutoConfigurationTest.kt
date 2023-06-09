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

package me.ahoo.wow.spring.boot.starter.r2dbc

import me.ahoo.wow.r2dbc.ConnectionFactoryRegistrar
import me.ahoo.wow.r2dbc.EventStreamSchema
import me.ahoo.wow.r2dbc.SnapshotSchema
import me.ahoo.wow.sharding.ShardingRegistrar
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.SnapshotStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.store.EventStoreStorage
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class ShardingDataSourcingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .enableWow()
            .withPropertyValues(
                "${SnapshotProperties.STORAGE}=${SnapshotStorage.R2DBC_NAME}",
                "${EventStoreProperties.STORAGE}=${EventStoreStorage.R2DBC_NAME}",
            )
            .withPropertyValues("${DataSourceProperties.PREFIX}.type=sharding")
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.databases.event_stream_0.url=r2dbc:pool:mariadb://root:root@localhost:3306/event_stream_0?initialSize=8&maxSize=8&acquireRetry=3&maxLifeTime=PT30M",
            )
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.event-stream.order.database-algorithm=event_stream_db",
            )
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.event-stream.order.table-algorithm=order_event_stream_table",
            )
            .withPropertyValues("${DataSourceProperties.PREFIX}.sharding.algorithms.event_stream_db.type=mod")
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.algorithms.event_stream_db.mod.logic-name-prefix=event_stream_",
            )
            .withPropertyValues("${DataSourceProperties.PREFIX}.sharding.algorithms.event_stream_db.mod.divisor=2")
            .withPropertyValues("${DataSourceProperties.PREFIX}.sharding.algorithms.order_event_stream_table.type=mod")
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.algorithms.order_event_stream_table.mod.logic-name-prefix=order_event_stream_",
            )
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.algorithms.order_event_stream_table.mod.divisor=2",
            )
            .withPropertyValues("${DataSourceProperties.PREFIX}.sharding.snapshot.order.database-algorithm=snapshot_db")
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.snapshot.order.table-algorithm=order_snapshot_table",
            )
            .withPropertyValues("${DataSourceProperties.PREFIX}.sharding.algorithms.snapshot_db.type=single")
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.algorithms.snapshot_db.single.node=snapshot_db",
            )
            .withPropertyValues("${DataSourceProperties.PREFIX}.sharding.algorithms.order_snapshot_table.type=single")
            .withPropertyValues(
                "${DataSourceProperties.PREFIX}.sharding.algorithms.order_snapshot_table.single.node=snapshot_table",
            )
            .withUserConfiguration(
                R2dbcAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(ShardingRegistrar::class.java)
                    .hasSingleBean(ConnectionFactoryRegistrar::class.java)
                    .hasBean("eventStreamDatabase")
                    .hasBean("snapshotDatabase")
                    .hasSingleBean(EventStreamSchema::class.java)
                    .hasSingleBean(SnapshotSchema::class.java)
            }
    }
}
