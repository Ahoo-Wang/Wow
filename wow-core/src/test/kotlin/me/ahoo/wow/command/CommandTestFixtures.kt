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
import me.ahoo.wow.api.annotation.AggregateName
import me.ahoo.wow.api.annotation.AggregateVersion
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OwnerId
import me.ahoo.wow.api.annotation.TenantId
import me.ahoo.wow.api.annotation.VoidCommand
import me.ahoo.wow.modeling.MaterializedNamedAggregate

internal const val COMMAND_FIXTURE_CONTEXT = "command-fixture"
internal const val COMMAND_FIXTURE_AGGREGATE = "account"
internal const val COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE =
    "$COMMAND_FIXTURE_CONTEXT.$COMMAND_FIXTURE_AGGREGATE"
internal const val COMMAND_FIXTURE_CUSTOM_NAME = "custom-account-command"

internal val commandFixtureNamedAggregate =
    MaterializedNamedAggregate(COMMAND_FIXTURE_CONTEXT, COMMAND_FIXTURE_AGGREGATE)

internal data class AccountCommand(
    @AggregateId val id: String,
    @AggregateVersion val version: Int? = null,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

@CreateAggregate
internal data class CreateAccountCommand(
    @AggregateId val id: String,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

@AllowCreate
internal data class UpsertAccountCommand(
    @AggregateId val id: String,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

@VoidCommand
internal data class VoidAccountCommand(
    @AggregateId val id: String,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

@Name(COMMAND_FIXTURE_CUSTOM_NAME)
internal data class NamedAccountCommand(
    @AggregateId val id: String,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

internal data class TenantAccountCommand(
    @AggregateId val id: String,
    @TenantId val tenantId: String,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

internal data class OwnerAccountCommand(
    @AggregateId val id: String,
    @OwnerId val ownerId: String,
    @AggregateName val aggregate: String = COMMAND_FIXTURE_NAMED_AGGREGATE_VALUE
)

internal data class FactoryTargetCommand(val value: String)
