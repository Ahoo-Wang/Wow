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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.SnapshotRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Flux
import java.util.Collections
import java.util.concurrent.TimeUnit

object SystemQuerySchemaSource : QuerySchemaSource {
    override val priority: Int = Int.MIN_VALUE

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> =
        Flux.just(declaration(context.model))

    fun declaration(model: QueryModel): QuerySchemaDeclaration {
        if (model != QueryModel.SNAPSHOT) {
            throw QuerySchemaValidationException("Unsupported System query model: [$model].")
        }
        return SNAPSHOT_DECLARATION
    }

    private val SNAPSHOT_DECLARATION = QuerySchemaDeclaration(
        Collections.unmodifiableMap(
            linkedMapOf(
                MessageRecords.CONTEXT_NAME.stringField(),
                MessageRecords.AGGREGATE_NAME.stringField(),
                MessageRecords.AGGREGATE_ID.stringField(),
                MessageRecords.TENANT_ID.stringField(),
                MessageRecords.OWNER_ID.stringField(),
                MessageRecords.SPACE_ID.stringField(),
                MessageRecords.VERSION.integerField(),
                StateAggregateRecords.EVENT_ID.stringField(),
                StateAggregateRecords.FIRST_OPERATOR.stringField(),
                StateAggregateRecords.OPERATOR.stringField(),
                StateAggregateRecords.FIRST_EVENT_TIME.epochField(),
                StateAggregateRecords.EVENT_TIME.epochField(),
                StateAggregateRecords.STATE.objectField(),
                StateAggregateRecords.TAGS.objectField(DeclarationValue.Set(true)),
                StateAggregateRecords.DELETED.booleanField(),
                SnapshotRecords.SNAPSHOT_TIME.epochField(),
            ),
        ),
    )

    private fun String.stringField() = field(QueryValueType.STRING)

    private fun String.integerField() = field(QueryValueType.INTEGER)

    private fun String.booleanField() = field(QueryValueType.BOOLEAN)

    private fun String.objectField(
        dynamicChildren: DeclarationValue<Boolean> = DeclarationValue.Unset,
    ) = field(QueryValueType.OBJECT, dynamicChildren = dynamicChildren)

    private fun String.epochField() = field(
        QueryValueType.INTEGER,
        DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)),
    )

    private fun String.field(
        valueType: QueryValueType,
        semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
        dynamicChildren: DeclarationValue<Boolean> = DeclarationValue.Unset,
    ): Pair<LogicalField, QueryFieldDeclaration> = LogicalField(this) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(valueType)),
        nullable = DeclarationValue.Set(false),
        required = DeclarationValue.Set(true),
        cardinality = DeclarationValue.Set(QueryCardinality.SINGLE),
        semanticType = semanticType,
        dynamicChildren = dynamicChildren,
    )
}
