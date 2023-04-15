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

package me.ahoo.wow.r2dbc

import me.ahoo.cosid.sharding.ModCycle
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.sharding.CosIdAggregateIdSharding
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

internal class ShardingDatabaseTest {
    private val namedAggregate = MaterializedNamedAggregate("test", "ShardingDatabaseTest")
    private val database1 = ConnectionFactoryProviders.create(1)
    private val database2 = ConnectionFactoryProviders.create(1)
    private val divisor = 2
    private val databaseSharding =
        CosIdAggregateIdSharding(
            mapOf(namedAggregate to ModCycle(divisor, "database_")),
        )
    private val shardingDatabase = ShardingDatabase(
        SimpleConnectionFactoryRegistrar(
            mutableMapOf(
                "database_1" to database1,
                "database_2" to database2,
            ),
        ),
        databaseSharding,
    )

    @Test
    fun create() {
        shardingDatabase.createConnection(namedAggregate.asAggregateId("0TEpCw9e0001001")).toMono()
            .test()
            .consumeNextWith {
                it.close().toMono().subscribe()
            }.verifyComplete()
    }
}
