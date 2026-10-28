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

package me.ahoo.wow.query.index

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryStorageType

/**
 * The `x-wow-index` indexes of one aggregate's snapshot query model, planned against the schema its storage's facts
 * compiled to: each declared [Index] with its [keys][Index.keys] resolved to where the storage keeps them. The core
 * plans it once, refusing what no storage can index (a key the model does not know, or one the storage neither
 * matches, ranges nor sorts); a backend applies its own rules on top, such as how many keys may hold many values
 * ([Key.cardinality]), and creates or checks the indexes as its `ddl` says. [logical] is the model's logical
 * definition, for a storage that maps the model's values rather than indexing keys.
 */
class QueryIndexPlan @InternalWowApi constructor(
    val logical: LogicalQuerySchema,
    val indexes: List<Index>,
) {
    /** One declared index: its [name] and its [keys], in the declared order. */
    class Index @InternalWowApi constructor(val name: String, val keys: List<Key>) {
        override fun equals(other: Any?): Boolean =
            this === other || other is Index && name == other.name && keys == other.keys

        override fun hashCode(): Int = 31 * name.hashCode() + keys.hashCode()

        override fun toString(): String = "Index(name=$name, keys=$keys)"
    }

    /**
     * One key of an index: the logical [field] the declaration names, the [physicalField] the storage keeps it at for
     * a sort, a range or an exact match (in that order of preference), its native [storageTypes] there (`null` when
     * the storage does not know them), and whether it holds one value per record or many ([cardinality], `null`
     * when unknown).
     */
    class Key @InternalWowApi constructor(
        val field: QueryField,
        val physicalField: QueryField,
        val storageTypes: Set<QueryStorageType>?,
        val cardinality: QueryCardinality?,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            other is Key && field == other.field && physicalField == other.physicalField &&
            storageTypes == other.storageTypes && cardinality == other.cardinality

        override fun hashCode(): Int = listOf(field, physicalField, storageTypes, cardinality).hashCode()

        override fun toString(): String =
            "Key(field=$field, physicalField=$physicalField, storageTypes=$storageTypes, cardinality=$cardinality)"
    }

    @InternalWowApi
    companion object {
        /** The capabilities whose binding an index key takes its location from: those an index serves. */
        private val INDEXED = listOf(QueryCapability.SORT, QueryCapability.RANGE, QueryCapability.EXACT_MATCH)

        /** The plan of [declarations] on [schema]; fails, naming the index and the key, on a key it cannot index. */
        @JvmStatic
        @InternalWowApi
        fun of(schema: QueryModelSchema, declarations: List<QueryIndexDeclaration>): QueryIndexPlan =
            QueryIndexPlan(
                schema.definition,
                declarations.map { declaration ->
                    Index(declaration.name, declaration.keys.map { key(schema, declaration, it) })
                },
            )

        private fun key(schema: QueryModelSchema, declaration: QueryIndexDeclaration, key: String): Key {
            val field = requireNotNull(schema.field(QueryField(key))) {
                "Index [${declaration.name}] has the key [$key], which the query model does not know."
            }
            val binding = requireNotNull(INDEXED.firstNotNullOfOrNull(field::binding)) {
                "Index [${declaration.name}] has the key [$key], which the storage neither matches, ranges nor sorts."
            }
            return Key(field.logicalField, binding.physicalField, binding.storageTypes, field.value.cardinality)
        }
    }
}
