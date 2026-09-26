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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.QueryFieldSchema

/**
 * One field reference of an [AdmittedQuery], resolved once by admission for this request.
 *
 * A backend reads it instead of looking the field up, and reads only the facts translation needs: the reference's
 * absolute logical path, the physical container of the element scope the reference sits in, the physical field bound
 * for the capability the reference was admitted with (every map key of the path already substituted), the field's
 * response location, how many values a record holds and how it stores time. It does not rewrite the reference's name.
 */
class ResolvedField internal constructor(
    /** The absolute logical path: the reference appended to its enclosing element scopes. */
    val logicalField: QueryField,
    /** The model's record of the field, which admission checked the reference against. */
    internal val definition: QueryFieldSchema,
    /** The absolute physical container of the enclosing element scope, or `null` at the root. */
    val physicalParent: QueryField?,
    /** The capability the reference was admitted with, or `null` for a projected field. */
    val capability: QueryCapability?,
    /** The absolute physical field bound for [capability], or the projection field of a projected field. */
    val physicalField: QueryField,
) {
    /** The element scopes the field lies in, outermost first. */
    val elementAncestors: List<QueryField>
        get() = definition.elementAncestors.orEmpty()

    /** Where the field sits in a stored record as storage returns it, when storage names that location. */
    val responseField: QueryField?
        get() = definition.responseField

    /** How many values one record holds for the field; `null` when its value is unknown or mixes both. */
    val cardinality: QueryCardinality?
        get() = definition.effective.cardinality

    /**
     * How the field stores time, or `null` when it declares no single encoding. A reference admitted for a date
     * group or date difference always stores a [Temporal.Date] or a [Temporal.Epoch].
     */
    val temporal: Temporal?
        get() = definition.effective.temporal

    /** [physicalField] relative to [physicalParent]; the physical field itself at the root. */
    val relativePhysicalField: QueryField
        get() = physicalParent?.let(physicalField::relativeTo) ?: physicalField

    override fun toString(): String = "ResolvedField(logical=$logicalField, physical=$physicalField, capability=$capability)"
}
