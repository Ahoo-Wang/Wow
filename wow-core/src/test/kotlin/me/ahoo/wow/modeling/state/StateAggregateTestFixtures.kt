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

package me.ahoo.wow.modeling.state

import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.event.AggregateDeleted
import me.ahoo.wow.api.event.AggregateRecovered
import me.ahoo.wow.api.event.IgnoreSourcing
import me.ahoo.wow.api.event.OwnerTransferred
import me.ahoo.wow.api.event.SpaceTransferred
import me.ahoo.wow.api.exception.ErrorInfo

class NoArgState

data class IdState(val id: String)

data class TenantState(val id: String, val tenantId: String)

data class AwareState(val id: String) : ReadOnlyStateAggregateAware<AwareState> {
    var readOnlyStateAggregate: ReadOnlyStateAggregate<AwareState>? = null
        private set

    override fun setReadOnlyStateAggregate(readOnlyStateAggregate: ReadOnlyStateAggregate<AwareState>) {
        this.readOnlyStateAggregate = readOnlyStateAggregate
    }
}

data class TagExtractingState(val id: String) : StateAggregateTagsExtractor<TagExtractingState> {
    override fun extract(source: ReadOnlyStateAggregate<TagExtractingState>): AbacTags =
        mapOf("state" to listOf(source.state.id))
}

data class TestOwnerTransferred(override val toOwnerId: String) : OwnerTransferred

data class TestSpaceTransferred(override val toSpaceId: String) : SpaceTransferred

object TestAggregateDeleted : AggregateDeleted

object TestAggregateRecovered : AggregateRecovered

data class UnknownStateEvent(val value: String)

data class IgnoredErrorEvent(
    override val errorCode: String,
    override val errorMsg: String,
) : ErrorInfo, IgnoreSourcing
