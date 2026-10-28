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

import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.tck.StoreTck

/** The whole MongoDB store, opened through its provider as the host opens it, against every port's contract. */
class MongoStoreTck : StoreTck() {
    override fun config(): StoreConfig = StoreConfig.of(
        name = "tck",
        type = MongoStoreProvider.TYPE,
        options = mapOf(MongoStoreProvider.DATABASE to MongoLogFixture.DATABASE),
        resources = StoreResources.of(mapOf("client" to MongoLogFixture.client)),
    )

    /** It serves queries. */
    override val facets: List<Class<*>> = listOf(StoreQueries::class.java)

    /** A decimal beyond what BSON's Decimal128 holds. */
    override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
        events = listOf(EventRecord("x.aggregate.Priced", 1, """{"amount":1.234567890123456789012345678901234567890}""")),
    )
}
