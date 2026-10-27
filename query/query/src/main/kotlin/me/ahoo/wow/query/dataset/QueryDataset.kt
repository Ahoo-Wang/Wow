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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.MaskStrategyResolver
import me.ahoo.wow.query.schema.QueryFieldDeclarationBuilder
import me.ahoo.wow.query.schema.QueryMask
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaDeclarationBuilder
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode

/**
 * Which columns of a dataset hold its caller scope: the [tenant] (or, for a [platform]-level dataset, none), the
 * [owner] and the [space], and the column each ABAC tag key is bound to ([tags]). A dataset either
 * names its tenant column or declares that it is platform-level, never both.
 */
@ExperimentalWowApi
class QueryDatasetScope @InternalWowApi constructor(
    val tenant: QueryField? = null,
    val owner: QueryField? = null,
    val space: QueryField? = null,
    /** The column of each ABAC tag key: a string column holds one tag value per record, a string-array one several. */
    val tags: Map<String, QueryField> = emptyMap(),
    /** The dataset has no tenant column: every record belongs to [PLATFORM_TENANT]. */
    val platform: Boolean = false,
) {
    init {
        require((tenant != null) != platform) {
            "A dataset declares either its tenant column or that it is platform-level, and not both."
        }
        require(tags.keys.none(String::isBlank)) { "A dataset's ABAC tag key must not be blank." }
    }

    /** The one tenant every record of a [platform] dataset belongs to; `null` for a dataset with a tenant column. */
    val fixedTenantId: String?
        get() = if (platform) PLATFORM_TENANT else null

    /** Every column this scope names. */
    val fields: List<QueryField>
        get() = listOfNotNull(tenant, owner, space) + tags.values

    companion object {
        /** The tenant of a platform-level dataset: the default tenant of a principal without one. */
        const val PLATFORM_TENANT: String = "(0)"

        /** A [QueryDatasetScope] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            tenant: QueryField? = null,
            owner: QueryField? = null,
            space: QueryField? = null,
            tags: Map<String, QueryField> = emptyMap(),
            platform: Boolean = false,
        ): QueryDatasetScope = QueryDatasetScope(tenant, owner, space, tags, platform)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryDatasetScope && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "QueryDatasetScope(tenant=$tenant, owner=$owner, space=$space, tags=$tags, platform=$platform)"

    private fun valueFields(): List<Any?> = listOf(tenant, owner, space, tags, platform)
}

/**
 * A dataset (the `DATASET` query model): a table-like record set, such as a BI wide table or the ClickHouse Sink's
 * `_state_flat` view, declared at runtime or in a file rather than in the Manifest.
 * Its fields are its [declaration], at the record root; its record layout is its [profile].
 *
 * [name] is `{context}.{dataset}`: a dataset takes a name slot of its context, beside that context's aggregates, and
 * the query engine keys it as it keys an aggregate type. [storage] is the backend's binding of the dataset (a
 * ClickHouse table, say), opaque to the core: a [DatasetQueryBackendFactory] reads it.
 *
 * A dataset whose [unavailable] names a reason is served but cannot be queried: each of its queries and its
 * descriptor are answered `wow.query.dataset-unavailable` (HTTP 409) with that reason, fixed words such as
 * `needs revision` (7.2b-2), instead of reading a table that may no longer hold what it declares.
 */
@ExperimentalWowApi
class QueryDataset @InternalWowApi constructor(
    val name: AggregateType,
    val scope: QueryDatasetScope,
    val declaration: QuerySchemaDeclaration,
    /** The column that identifies one record: the target of id filters and the tie-breaker of every cursor. */
    val identity: QueryField? = null,
    /** When the record last changed: the default `orderBy` of FIRST and LAST metrics. */
    val eventTime: QueryField? = null,
    val title: String? = null,
    val storage: ObjectNode = WowJson.mapper.createObjectNode(),
    /** Why the dataset cannot be queried now, or `null` when it can. */
    val unavailable: String? = null,
) {
    /** This dataset, [unavailable] for [reason] now, or available again with null. */
    fun withUnavailable(reason: String?): QueryDataset =
        QueryDataset(name, scope, declaration, identity, eventTime, title, storage, reason)

    init {
        require(unavailable == null || unavailable.isNotBlank()) { "Dataset [$name] names why it is unavailable." }
        val declared = declaration.fields.keys
        require(declared.isNotEmpty()) { "Dataset [$name] declares no field." }
        (scope.fields + listOfNotNull(identity, eventTime)).forEach { field ->
            require(field in declared) { "Dataset [$name] names undeclared field [$field]." }
        }
    }

    /** The record layout of this dataset: its identity, event time and scope columns. */
    val profile: DatasetQueryModelProfile by lazy { DatasetQueryModelProfile(this) }

    companion object {
        /** The dataset [block] declares under [name]. */
        @JvmStatic
        fun of(name: AggregateType, block: QueryDatasetBuilder.() -> Unit): QueryDataset =
            QueryDatasetBuilder(name).apply(block).build()

        /**
         * The dataset [document] declares under [name], in the format of a dataset file ([QueryDatasetProperties]);
         * an [IllegalArgumentException] says what is wrong with it.
         */
        @JvmStatic
        fun parse(name: AggregateType, document: String): QueryDataset = parseDatasetDocument(name, document)

        /** The dataset name `{context}.{dataset}`: the context is everything before the last dot. */
        @JvmStatic
        fun nameOf(name: String): AggregateType {
            val separator = name.lastIndexOf('.')
            require(separator > 0 && separator < name.length - 1) {
                "Dataset name [$name] must be {context}.{dataset}."
            }
            return AggregateType(name.substring(0, separator), name.substring(separator + 1))
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryDataset && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "QueryDataset(name=$name, scope=$scope, declaration=$declaration, identity=$identity, eventTime=$eventTime, " +
            "title=$title, storage=$storage, unavailable=$unavailable)"

    private fun valueFields(): List<Any?> = listOf(
        name,
        scope,
        declaration,
        identity,
        eventTime,
        title,
        storage,
        unavailable
    )
}

/** Declares a [QueryDataset] in code: its profile columns, its fields and their sensitivity. */
@ExperimentalWowApi
class QueryDatasetBuilder(private val name: AggregateType) {
    private var tenant: QueryField? = null
    private var platform: Boolean = false
    private var owner: QueryField? = null
    private var space: QueryField? = null
    private val tags = linkedMapOf<String, QueryField>()
    private var identity: QueryField? = null
    private var eventTime: QueryField? = null
    private var title: String? = null
    private var storage: ObjectNode = WowJson.mapper.createObjectNode()
    private val fields = QuerySchemaDeclarationBuilder()
    private val sensitive = linkedMapOf<QueryField, MaskRule>()

    fun tenant(field: String) {
        tenant = QueryField(field)
    }

    /** Declares the dataset platform-level: its table has no tenant column ([QueryDatasetScope.platform]). */
    fun platform() {
        platform = true
    }

    fun owner(field: String) {
        owner = QueryField(field)
    }

    fun space(field: String) {
        space = QueryField(field)
    }

    /** Binds the ABAC tag [key] to [field], a string or string-array column. */
    fun tag(key: String, field: String) {
        tags[key] = QueryField(field)
    }

    fun identity(field: String) {
        identity = QueryField(field)
    }

    fun eventTime(field: String) {
        eventTime = QueryField(field)
    }

    fun title(value: String) {
        title = value
    }

    /** The backend's binding of the dataset, such as `{"table": "db.shop_order_state_flat"}`. */
    fun storage(value: ObjectNode) {
        storage = value
    }

    fun field(field: String, block: QueryFieldDeclarationBuilder.() -> Unit) = fields.field(field, block)

    /**
     * Masks [field] in results and restricts how it is compared, as `x-wow-sensitive` does on a Manifest state field;
     * a custom [QueryMask.Strategy] is resolved by [resolver].
     */
    fun sensitive(
        field: String,
        level: SensitivityLevel,
        mask: QueryMask = QueryMask.FULL,
        resolver: MaskStrategyResolver = MaskStrategyResolver.NONE,
    ) {
        sensitive[QueryField(field)] = MaskRule(level, mask, resolver)
    }

    fun build(): QueryDataset {
        val declared = fields.build().fields
        sensitive.keys.forEach { require(it in declared) { "Dataset [$name] masks undeclared field [$it]." } }
        val masked = declared.mapValues { (field, declaration) ->
            sensitive[field]?.let { declaration.copy(maskRule = DeclarationValue.Set(it)) } ?: declaration
        }
        require(tenant != null || platform) {
            "Dataset [$name] must declare its tenant field or that it is platform-level."
        }
        return QueryDataset(
            name = name,
            scope = QueryDatasetScope(
                tenant = tenant,
                owner = owner,
                space = space,
                tags = tags.toMap(),
                platform = platform,
            ),
            declaration = QuerySchemaDeclaration(masked),
            identity = identity,
            eventTime = eventTime,
            title = title,
            storage = storage,
        )
    }
}
