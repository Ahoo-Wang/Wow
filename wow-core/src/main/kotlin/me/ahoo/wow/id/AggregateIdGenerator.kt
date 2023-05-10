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

package me.ahoo.wow.id

import me.ahoo.cosid.IdGenerator
import me.ahoo.wow.annotation.OrderComparator
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import org.slf4j.LoggerFactory
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

private val AGGREGATE_ID_GENERATORS: MutableMap<NamedAggregate, IdGenerator> =
    ConcurrentHashMap<NamedAggregate, IdGenerator>()

object AggregateIdGeneratorRegistrar :
    Map<NamedAggregate, IdGenerator> by AGGREGATE_ID_GENERATORS {
    private val log = LoggerFactory.getLogger(AggregateIdGeneratorRegistrar::class.java)
    private val factories: List<AggregateIdGeneratorFactory> by lazy {
        return@lazy ServiceLoader.load(AggregateIdGeneratorFactory::class.java)
            .sortedWith(OrderComparator)
    }

    fun getOrInitialize(key: NamedAggregate): IdGenerator {
        return AGGREGATE_ID_GENERATORS.computeIfAbsent(key) { _ ->
            factories.firstNotNullOfOrNull {
                if (log.isInfoEnabled) {
                    log.info("Load $it to create [$key]'s AggregateIdGenerator.")
                }
                val idGenerator = it.create(key)
                if (idGenerator == null) {
                    if (log.isInfoEnabled) {
                        log.info("Ignore: $it create [$key]'s AggregateIdGenerator is null.")
                    }
                } else {
                    if (log.isInfoEnabled) {
                        log.info("Setup $idGenerator to [$key]'s AggregateIdGenerator.")
                    }
                }
                idGenerator
            } ?: throw IllegalStateException("No AggregateIdGeneratorFactory found for [$key]'s AggregateIdGenerator.")
        }
    }

    fun generateId(key: NamedAggregate): String {
        return getOrInitialize(key).generateAsString()
    }
}

fun NamedAggregate.generateId(): String {
    return AggregateIdGeneratorRegistrar.generateId(materialize())
}
