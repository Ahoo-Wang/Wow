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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.spec.SystemField

/**
 * The record layout of one [QueryDataset]: the whole record is payload, its fields are the dataset's declaration, and
 * the system fields are the columns the dataset names. A dataset has no aggregate id and no deletion flag, so a filter
 * on either is an unknown field, and no default scope.
 */
@ExperimentalWowApi
class DatasetQueryModelProfile internal constructor(val dataset: QueryDataset) : QueryModelProfile(QueryModel.DATASET) {
    override val identityField: QueryField?
        get() = dataset.identity

    override val payloadField: QueryField?
        get() = null

    override val eventTimeField: QueryField?
        get() = dataset.eventTime

    /** The tenant column, or, for a platform-level dataset, the tenant it pins through [fixedTenantId]. */
    override val requiredScope: QueryField
        get() = dataset.scope.tenant ?: PLATFORM_SCOPE

    override val fixedTenantId: String?
        get() = dataset.scope.fixedTenantId

    /** The column each ABAC tag key is bound to; empty when the dataset takes no part in ABAC. */
    val tags: Map<String, QueryField>
        get() = dataset.scope.tags

    /** None: a dataset's fields are its declaration, supplied by [QueryDatasets.schemaSource]. */
    override val systemDeclaration: QuerySchemaDeclaration
        get() = EMPTY_DECLARATION

    override fun systemField(field: SystemField): QueryField? = when (field) {
        SystemField.IDENTITY -> dataset.identity
        SystemField.TENANT_ID -> dataset.scope.tenant
        SystemField.OWNER_ID -> dataset.scope.owner
        SystemField.SPACE_ID -> dataset.scope.space
        SystemField.AGGREGATE_ID, SystemField.DELETED -> null
    }

    override fun equals(other: Any?): Boolean = other is DatasetQueryModelProfile && dataset == other.dataset

    override fun hashCode(): Int = dataset.hashCode()

    override fun toString(): String = "DatasetQueryModelProfile(${dataset.name})"

    private companion object {
        val EMPTY_DECLARATION = QuerySchemaDeclaration(emptyMap())

        /** What a platform-level dataset's caller scope must pin: the tenant, which no column holds. */
        val PLATFORM_SCOPE = QueryField("tenantId")
    }
}
