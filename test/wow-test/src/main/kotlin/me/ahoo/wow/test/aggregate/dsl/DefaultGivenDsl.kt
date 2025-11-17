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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.test.aggregate.GivenStage

/**
 * Default implementation of the GivenDsl interface for basic given stage operations.
 *
 * This class provides a straightforward implementation that delegates all operations
 * to the underlying GivenStage, serving as a bridge between the DSL interface
 * and the actual test execution logic.
 *
 * @param S the state type of the aggregate
 * @property delegate the underlying GivenStage that handles the test setup
 */
class DefaultGivenDsl<S : Any>(
    override val context: AggregateDslContext<S>,
    override val delegate: GivenStage<S>
) : AbstractGivenStageDsl<S>()
