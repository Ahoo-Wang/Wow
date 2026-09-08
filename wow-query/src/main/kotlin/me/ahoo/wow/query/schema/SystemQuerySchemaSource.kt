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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.event.DomainEventRecords
import me.ahoo.wow.serialization.state.SnapshotRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import reactor.core.publisher.Flux
import java.util.Collections
import java.util.concurrent.TimeUnit

object SystemQuerySchemaSource : QuerySchemaSource {
    override val priority: Int = Int.MIN_VALUE

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> =
        Flux.just(declaration(context.model))

    fun declaration(model: QueryModel): QuerySchemaDeclaration = when (model) {
        QueryModel.SNAPSHOT -> SNAPSHOT_DECLARATION
        QueryModel.EVENT_STREAM -> EVENT_STREAM_DECLARATION
        else -> throw QuerySchemaValidationException("Unsupported System query model: [$model].")
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
                StateAggregateRecords.STATE.payloadField(),
                StateAggregateRecords.TAGS.objectField(dynamic = true).let { (field, declaration) ->
                    field to declaration.copy(
                        additionalProperties = DeclarationValue.Set(
                            QueryFieldDeclaration(
                                nullable = DeclarationValue.Set(false),
                                items = DeclarationValue.Set(
                                    QueryFieldDeclaration(
                                        valueTypes = DeclarationValue.Set(setOf(QueryValueType.STRING)),
                                        nullable = DeclarationValue.Set(false),
                                    )
                                ),
                            )
                        )
                    )
                },
                StateAggregateRecords.DELETED.booleanField(),
                SnapshotRecords.SNAPSHOT_TIME.epochField(),
            ),
        ),
    )

    private val EVENT_STREAM_DECLARATION = QuerySchemaDeclaration(
        Collections.unmodifiableMap(
            linkedMapOf(
                MessageRecords.ID.stringField(),
                MessageRecords.CONTEXT_NAME.stringField(),
                MessageRecords.AGGREGATE_NAME.stringField(),
                MessageRecords.HEADER.objectField(dynamic = true),
                MessageRecords.AGGREGATE_ID.stringField(),
                MessageRecords.TENANT_ID.stringField(),
                MessageRecords.OWNER_ID.stringField(),
                MessageRecords.SPACE_ID.stringField(),
                MessageRecords.COMMAND_ID.stringField(),
                MessageRecords.REQUEST_ID.stringField(),
                MessageRecords.VERSION.integerField(),
                MessageRecords.CREATE_TIME.epochField(),
                QueryField(MessageRecords.BODY) to QueryFieldDeclaration(
                    kind = DeclarationValue.Set(QueryValueKind.ARRAY),
                    nullable = DeclarationValue.Set(false),
                    required = DeclarationValue.Set(true),
                    items = DeclarationValue.Set(QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)))),
                ),
                "${MessageRecords.BODY}.${MessageRecords.ID}".stringField(),
                "${MessageRecords.BODY}.${MessageRecords.NAME}".stringField(),
                "${MessageRecords.BODY}.${DomainEventRecords.REVISION}".stringField(),
                "${MessageRecords.BODY}.${MessageRecords.BODY_TYPE}".stringField(),
                "${MessageRecords.BODY}.${MessageRecords.BODY}".payloadField(),
            ),
        ),
    )

    private fun String.payloadField() = QueryField(this) to QueryFieldDeclaration(
        nullable = DeclarationValue.Set(false),
        required = DeclarationValue.Set(true),
    )

    private fun String.stringField() = field(QueryValueType.STRING)

    private fun String.integerField() = field(QueryValueType.INTEGER)

    private fun String.booleanField() = field(QueryValueType.BOOLEAN)

    private fun String.objectField(dynamic: Boolean = false) = field(
        QueryValueType.OBJECT
    ).let { (field, declaration) ->
        field to declaration.copy(additionalProperties = if (dynamic) DeclarationValue.Set(QueryFieldDeclaration()) else DeclarationValue.Unset)
    }

    private fun String.epochField() = field(
        QueryValueType.INTEGER,
        DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)),
    )

    private fun String.field(
        valueType: QueryValueType,
        semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    ): Pair<QueryField, QueryFieldDeclaration> = QueryField(this) to QueryFieldDeclaration(
        valueTypes = DeclarationValue.Set(setOf(valueType)),
        nullable = DeclarationValue.Set(false),
        required = DeclarationValue.Set(true),
        semanticType = semanticType,
    )
}
