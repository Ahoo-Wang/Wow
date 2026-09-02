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

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import me.ahoo.wow.schema.typed.AggregateIdDefinitionProvider
import me.ahoo.wow.schema.typed.AggregatedDomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.CommandDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventDefinitionProvider
import me.ahoo.wow.schema.typed.DomainEventStreamDefinitionProvider
import me.ahoo.wow.schema.typed.EnumTextDefinitionProvider
import me.ahoo.wow.schema.typed.JsonNodeDefinitionProvider
import me.ahoo.wow.schema.typed.MapDefinitionProvider
import me.ahoo.wow.schema.typed.SnapshotDefinitionProvider
import me.ahoo.wow.schema.typed.StateAggregateDefinitionProvider
import me.ahoo.wow.schema.typed.StateEventDefinitionProvider
import me.ahoo.wow.schema.typed.query.QueryFieldDefinitionProvider
import me.ahoo.wow.schema.typed.query.QuerySchemaValueDefinitionProvider
import me.ahoo.wow.schema.web.ServerSentEventCustomDefinitionProvider

internal object WowDefinitionProviderRegistry {
    val providers: List<CustomDefinitionProviderV2> = listOf(
        AggregateIdDefinitionProvider,
        CommandDefinitionProvider,
        DomainEventDefinitionProvider,
        DomainEventStreamDefinitionProvider,
        AggregatedDomainEventStreamDefinitionProvider,
        StateAggregateDefinitionProvider,
        SnapshotDefinitionProvider,
        StateEventDefinitionProvider,
        ServerSentEventCustomDefinitionProvider,
        QueryFieldDefinitionProvider,
        QuerySchemaValueDefinitionProvider,
        JsonNodeDefinitionProvider,
        MapDefinitionProvider,
        EnumTextDefinitionProvider
    )
}
