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

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.tck.container.MongoTestFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.kotlin.core.publisher.toFlux
import reactor.kotlin.core.publisher.toMono

abstract class SchemaInitializerSpec {
    @JvmField
    @RegisterExtension
    val mongo = MongoTestFixture()

    @Test
    fun `should initialize all aggregate schemas`() {
        initAllAggregateSchema(mongo.database())
    }

    abstract fun initAllAggregateSchema(database: MongoDatabase)
    abstract fun initAggregateSchema(database: MongoDatabase, namedAggregate: NamedAggregate)
    abstract fun getCollectionName(namedAggregate: NamedAggregate): String

    @Test
    fun `should initialize aggregate schema`() {
        val database = mongo.database()
        val aggregateName = "testInitSchema"
        val namedAggregate = me.ahoo.wow.modeling.MaterializedNamedAggregate("", aggregateName)
        val collectionName = getCollectionName(namedAggregate)
        database.getCollection(collectionName).drop().toMono().block()
        initAggregateSchema(database, namedAggregate)
        database.listCollectionNames().toFlux().collectList().block()!!.let {
            it.assert().contains(collectionName)
        }
        database.getCollection(collectionName).drop().toMono().block()
    }
}
