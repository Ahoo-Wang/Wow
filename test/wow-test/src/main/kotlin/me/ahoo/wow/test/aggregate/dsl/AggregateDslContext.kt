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

import me.ahoo.wow.test.aggregate.ExpectStage

interface AggregateDslContext<S : Any> {
    val expectStages: MutableMap<String, ExpectStage<S>>

    fun setExpectStage(name: String, expectStage: ExpectStage<S>) {
        if (name.isBlank()) {
            return
        }
        require(!expectStages.containsKey(name)) {
            "ExpectStage[$name] already exists!"
        }
        expectStages[name] = expectStage
    }

    fun getExpectStage(name: String): ExpectStage<S> {
        require(name.isNotBlank()) {
            "ExpectStage name can not be blank!"
        }
        return requireNotNull(expectStages[name]) {
            "ExpectStage[$name] not found!"
        }
    }
}

class DefaultAggregateDslContext<S : Any>(
    override val expectStages: MutableMap<String, ExpectStage<S>> = mutableMapOf()
) : AggregateDslContext<S>

interface AggregateDslContextCapable<S : Any> {
    val context: AggregateDslContext<S>
}
