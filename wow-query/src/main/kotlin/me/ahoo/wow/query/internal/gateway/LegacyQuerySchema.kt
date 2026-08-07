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

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.query.internal.model.QueryDocumentKind
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.normalization.PredicateOperator
import me.ahoo.wow.query.internal.normalization.SystemFieldKind
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.LogicalFieldType
import me.ahoo.wow.query.internal.schema.Nullability
import me.ahoo.wow.query.internal.schema.Presence
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId
import me.ahoo.wow.query.internal.schema.QueryFieldSchema
import me.ahoo.wow.serialization.MessageRecords

/**
 * Bootstrap schema for legacy-compatible execution.
 *
 * It deliberately declares only framework-owned system fields. User paths remain unknown and therefore produce an
 * explicit COMPATIBLE fallback instead of inventing field types or backend capabilities.
 */
internal fun legacyQuerySchema(target: QueryTarget): QueryDocumentSchema =
    QueryDocumentSchema(
        target,
        fields = buildList {
            val identityAlias = when (target.documentKind) {
                QueryDocumentKind.SNAPSHOT -> MessageRecords.AGGREGATE_ID
                QueryDocumentKind.EVENT_STREAM -> MessageRecords.ID
            }
            add(
                textSystemField(
                    SystemFieldKind.IDENTITY,
                    Presence.REQUIRED,
                    sortable = true,
                    logicalAliases = listOf(path(identityAlias)),
                ),
            )
            add(
                textSystemField(
                    SystemFieldKind.AGGREGATE_ID,
                    Presence.REQUIRED,
                    logicalAliases = if (target.documentKind == QueryDocumentKind.EVENT_STREAM) {
                        listOf(path(MessageRecords.AGGREGATE_ID))
                    } else {
                        emptyList()
                    },
                ),
            )
            add(
                textSystemField(
                    SystemFieldKind.TENANT_ID,
                    Presence.OPTIONAL,
                    logicalAliases = listOf(path(MessageRecords.TENANT_ID)),
                ),
            )
            add(
                textSystemField(
                    SystemFieldKind.OWNER_ID,
                    Presence.OPTIONAL,
                    logicalAliases = listOf(path(MessageRecords.OWNER_ID)),
                ),
            )
            add(
                textSystemField(
                    SystemFieldKind.SPACE_ID,
                    Presence.OPTIONAL,
                    logicalAliases = listOf(path(MessageRecords.SPACE_ID)),
                ),
            )
            if (target.documentKind == QueryDocumentKind.SNAPSHOT) {
                add(booleanSystemField(SystemFieldKind.DELETED, listOf(path(DELETED_FIELD))))
            }
        },
        searchScopes = emptyList(),
    )

private fun textSystemField(
    kind: SystemFieldKind,
    presence: Presence,
    sortable: Boolean = false,
    logicalAliases: Iterable<QueryFieldId.Path> = emptyList(),
): QueryFieldSchema =
    QueryFieldSchema(
        id = QueryFieldId.System(kind),
        type = LogicalFieldType.Text,
        presence = presence,
        nullability = Nullability.NON_NULL,
        allowedOperators = setOf(PredicateOperator.EQ, PredicateOperator.IN),
        capabilities = buildSet {
            add(FieldCapability.EXACT)
            add(FieldCapability.PROJECTABLE)
            if (sortable) {
                add(FieldCapability.SORTABLE)
            }
        },
        logicalAliases = logicalAliases,
    )

private fun booleanSystemField(
    kind: SystemFieldKind,
    logicalAliases: Iterable<QueryFieldId.Path> = emptyList(),
): QueryFieldSchema =
    QueryFieldSchema(
        id = QueryFieldId.System(kind),
        type = LogicalFieldType.Boolean,
        presence = Presence.REQUIRED,
        nullability = Nullability.NON_NULL,
        allowedOperators = setOf(
            PredicateOperator.EQ,
            PredicateOperator.IS_TRUE,
            PredicateOperator.IS_FALSE,
        ),
        capabilities = setOf(FieldCapability.EXACT, FieldCapability.PROJECTABLE),
        logicalAliases = logicalAliases,
    )

private fun path(field: String): QueryFieldId.Path = QueryFieldId.Path(listOf(field))

private const val DELETED_FIELD = "deleted"
