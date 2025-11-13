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
import me.ahoo.wow.api.modeling.NamedAggregate

/**
 * Functional interface for factories that create ID generators for aggregates.
 *
 * This interface provides a standard way to generate [IdGenerator] instances based on [NamedAggregate] information.
 * In Domain-Driven Design (DDD), this allows flexible generation of globally unique identifiers for different aggregates,
 * ensuring that ID generation strategies can be centralized and configured.
 *
 * @see NamedAggregate represents a named aggregate root
 * @see IdGenerator interface for generating aggregate identifiers
 */
fun interface AggregateIdGeneratorFactory {
    /**
     * Creates an ID generator for the specified aggregate.
     *
     * This method allows dynamic selection of ID generation strategies based on the aggregate's name.
     * If the given aggregate does not support ID generation or has no specific strategy, returns null.
     *
     * @param namedAggregate the aggregate instance containing name information
     * @return the ID generator, which may be null if no strategy is available
     */
    fun create(namedAggregate: NamedAggregate): IdGenerator?
}
