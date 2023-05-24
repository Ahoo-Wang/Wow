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

package me.ahoo.wow.command

import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.StaticAggregateId
import me.ahoo.wow.api.annotation.StaticTenantId
import me.ahoo.wow.api.annotation.TenantId

const val NAMED_COMMAND = "MockNamedCommand"

data class MockCommandWithExpectedAggregateVersion(
    @AggregateId val id: String,
    @AggregateVersion val version: Int?
)

@CreateAggregate
data class MockCreateCommand(
    @AggregateId val id: String
)

@CreateAggregate
class MockCreateCommandWithoutAggregateId

@Name(NAMED_COMMAND)
data class MockNamedCommand(@AggregateId val id: String)

data class MockCommandWithDefaultNamedId(val id: String)

data class MockCommandWithoutTargetAggregateId(val withoutId: String)

data class MockTenantIdCommand(@AggregateId val id: String, @TenantId val tenantId: String)

@StaticAggregateId("staticAggregateId")
@StaticTenantId
class MockStaticCommand

@StaticAggregateId("staticAggregateId")
@StaticTenantId
interface MockStaticId

class MockInheritStaticCommand : MockStaticId
