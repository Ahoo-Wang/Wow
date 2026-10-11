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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import tools.jackson.databind.node.ObjectNode

/**
 * The columns that bound a dataset's records to a caller: the tenant, the isolation boundary every caller scope pins,
 * optionally the owner and space, and the [tags] `AbacQueryPolicy` restricts by.
 *
 * Each ABAC tag key is bound to its own column, a string (one value per record) or an array of strings (several); a
 * record without the tag holds an empty string or an empty array there. A principal tag key the dataset binds no
 * column for is a tag no record carries.
 */
data class QueryDatasetScope(
    val tenant: QueryField,
    val owner: QueryField? = null,
    val space: QueryField? = null,
    val tags: Map<String, QueryField> = emptyMap(),
) {
    init {
        require(tags.keys.none(String::isBlank)) { "A dataset's ABAC tag key must not be blank." }
    }

    val fields: List<QueryField>
        get() = listOfNotNull(tenant, owner, space) + tags.values
}

/**
 * A declared dataset (design `2026-10-10-clickhouse-dataset-query-design.md` §4): a table-like record set, such as a
 * BI wide table, queried as the [QueryModel.DATASET] model of [namedAggregate].
 *
 * A dataset is named within a bounded context, as an aggregate is, and occupies the same name slot: gateways,
 * policies, observers and the Catalog address it by [namedAggregate] and tell it apart by its model. A dataset never
 * shares its name with an aggregate of its context ([QuerySchemaCatalog] refuses the pair), so a policy keyed by an
 * aggregate's name never applies to a dataset.
 *
 * Its record layout comes from the declaration, not from a type: [declaration] lists the fields a query may name, at
 * the record root, with their sensitivity (a dataset declaration is the one place outside a field annotation that
 * declares it, since a dataset's columns have no type to carry one); the profile facts name the [identity], the
 * [eventTime] and the [scope] columns among them. [storage] is the opaque binding its query backend reads, such as a
 * ClickHouse table.
 */
data class QueryDataset(
    val namedAggregate: MaterializedNamedAggregate,
    val scope: QueryDatasetScope,
    val declaration: QuerySchemaDeclaration,
    val identity: QueryField? = null,
    val eventTime: QueryField? = null,
    val title: String? = null,
    val storage: ObjectNode = JsonSerializer.createObjectNode(),
) {
    init {
        val declared = declaration.fields.keys
        require(declared.isNotEmpty()) { "Dataset [$namedAggregate] declares no field." }
        (scope.fields + listOfNotNull(identity, eventTime)).forEach { field ->
            require(field in declared) { "Dataset [$namedAggregate] names undeclared field [$field]." }
        }
    }

    /** The record layout of this dataset: its identity, event time and scope columns. */
    val profile: DatasetQueryModelProfile by lazy { DatasetQueryModelProfile(this) }

    companion object {
        @JvmStatic
        fun of(namedAggregate: NamedAggregate, block: QueryDatasetBuilder.() -> Unit): QueryDataset =
            QueryDatasetBuilder(namedAggregate.materialize()).apply(block).build()
    }
}

/** Declares a [QueryDataset] in code: its scope and profile columns, its fields and their sensitivity. */
class QueryDatasetBuilder(private val namedAggregate: MaterializedNamedAggregate) {
    private var tenant: QueryField? = null
    private var owner: QueryField? = null
    private var space: QueryField? = null
    private val tags = linkedMapOf<String, QueryField>()
    private var identity: QueryField? = null
    private var eventTime: QueryField? = null
    private var title: String? = null
    private var storage: ObjectNode = JsonSerializer.createObjectNode()
    private val fields = QuerySchemaDeclarationBuilder()
    private val sensitive = linkedMapOf<QueryField, MaskRule>()

    fun tenant(field: String) {
        tenant = QueryField(field)
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

    fun storage(value: ObjectNode) {
        storage = value
    }

    fun field(field: String, block: QueryFieldDeclarationBuilder.() -> Unit) = fields.field(field, block)

    /** Masks [field] in results and restricts how it is compared, as `@Sensitive` does on a typed model. */
    fun sensitive(field: String, level: SensitivityLevel, mask: Mask = Mask()) {
        sensitive[QueryField(field)] = MaskRule(level, mask)
    }

    fun build(): QueryDataset {
        val declared = fields.build().fields
        sensitive.keys.forEach { require(it in declared) { "Dataset [$namedAggregate] masks undeclared field [$it]." } }
        val masked = declared.mapValues { (field, declaration) ->
            sensitive[field]?.let { declaration.copy(maskRule = DeclarationValue.Set(it)) } ?: declaration
        }
        return QueryDataset(
            namedAggregate = namedAggregate,
            scope = QueryDatasetScope(
                tenant = requireNotNull(tenant) { "Dataset [$namedAggregate] must declare its tenant field." },
                owner = owner,
                space = space,
                tags = tags.toMap(),
            ),
            declaration = QuerySchemaDeclaration(masked),
            identity = identity,
            eventTime = eventTime,
            title = title,
            storage = storage,
        )
    }
}

/**
 * The record layout of one [dataset]: the whole record is payload, so fields and masks live at the root; the system
 * fields are the dataset's own columns, and one it does not declare (an aggregate id, a deletion flag) is not
 * carried. A dataset has no default scope: what a record's lifecycle means belongs to the dataset.
 */
class DatasetQueryModelProfile internal constructor(val dataset: QueryDataset) :
    QueryModelProfile(QueryModel.DATASET) {
    override val identityField: QueryField?
        get() = dataset.identity

    override val payloadField: QueryField?
        get() = null

    override val eventTimeField: QueryField?
        get() = dataset.eventTime

    override val requiredScope: QueryField
        get() = dataset.scope.tenant

    /** The column each ABAC tag key is bound to; empty when the dataset takes no part in ABAC. */
    val tags: Map<String, QueryField>
        get() = dataset.scope.tags

    /** None: a dataset's fields are its declaration, supplied by [QueryDatasets] as a schema source. */
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

    override fun toString(): String = "DatasetQueryModelProfile(${dataset.namedAggregate})"

    private companion object {
        val EMPTY_DECLARATION = QuerySchemaDeclaration(emptyMap())
    }
}

/** The datasets this instance serves, by name. */
class QueryDatasets(datasets: Collection<QueryDataset>) {
    private val byName: Map<MaterializedNamedAggregate, QueryDataset> = datasets.associateBy { it.namedAggregate }

    init {
        require(
            byName.size == datasets.size
        ) { "Datasets must have unique names: ${datasets.map { it.namedAggregate }}." }
    }

    val all: Collection<QueryDataset>
        get() = byName.values

    operator fun get(namedAggregate: NamedAggregate): QueryDataset? = byName[namedAggregate.materialize()]

    /** The profile of [context]'s model: the dataset's own for [QueryModel.DATASET], the built-in one otherwise. */
    fun profile(context: QuerySchemaContext): QueryModelProfile? = if (context.model == QueryModel.DATASET) {
        get(context.namedAggregate)?.profile
    } else {
        QueryModelProfile.of(context.model)
    }

    /**
     * The schema source of the datasets' fields: a dataset's declaration is the base of its model, as inference is
     * for an aggregate's, and other sources (a declaration file, a bean) may enrich it at a higher priority.
     */
    val schemaSource: QuerySchemaSource = object : QuerySchemaSource {
        override val priority: Int = QuerySchemaSourcePriority.INFERRED

        override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> =
            if (context.model == QueryModel.DATASET) {
                get(context.namedAggregate)?.let { Flux.just(it.declaration) } ?: Flux.empty()
            } else {
                Flux.empty()
            }
    }

    companion object {
        @JvmField
        val NONE: QueryDatasets = QueryDatasets(emptyList())
    }
}

/**
 * The keys of a dataset file (`META-INF/wow/dataset/{context}.{dataset}.json` or `config/wow/dataset/…`):
 *
 * ```json
 * {
 *   "title": "Orders with their customers",
 *   "identity": "order_id",
 *   "eventTime": "updated_at",
 *   "scope": { "tenant": "tenant_id", "owner": "owner_id", "tags": { "region": "region_tag" } },
 *   "storage": { "table": "bi_db.order_customer_wide" },
 *   "fields": {
 *     "order_id": { "types": ["STRING"], "nullable": false },
 *     "customer_phone": { "types": ["STRING"], "sensitive": { "level": "CONFIDENTIAL", "keepSuffix": 4 } }
 *   }
 * }
 * ```
 *
 * A field takes the query schema declaration keys ([QuerySchemaDeclarationProperties]) and `sensitive`, which only a
 * dataset may declare. `storage` is the query backend's own binding and is passed to it unread.
 */
object QueryDatasetProperties {
    const val TITLE = "title"
    const val IDENTITY = "identity"
    const val EVENT_TIME = "eventTime"
    const val SCOPE = "scope"
    const val TENANT = "tenant"
    const val OWNER = "owner"
    const val SPACE = "space"
    const val TAGS = "tags"
    const val STORAGE = "storage"
    const val SENSITIVE = "sensitive"
    const val LEVEL = "level"
    const val KEEP_PREFIX = "keepPrefix"
    const val KEEP_SUFFIX = "keepSuffix"

    internal val ROOT = setOf(TITLE, IDENTITY, EVENT_TIME, SCOPE, STORAGE, QuerySchemaDeclarationProperties.FIELDS)
    internal val SCOPE_PROPERTIES = setOf(TENANT, OWNER, SPACE, TAGS)
    internal val SENSITIVE_PROPERTIES = setOf(LEVEL, KEEP_PREFIX, KEEP_SUFFIX)
}

/**
 * Reads dataset files by name. The working directory's file (`config/wow/dataset/{context}.{dataset}.json`) replaces
 * the classpath's (`META-INF/wow/dataset/…`), so an operator can adjust a deployed dataset without a rebuild; on the
 * classpath a name must resolve to one file.
 */
class QueryDatasetFiles(private val resources: WowResourceLocator = WowResourceLocator()) {
    fun load(namedAggregate: NamedAggregate): QueryDataset {
        val name = namedAggregate.materialize()
        val key = "${name.contextName}.${name.aggregateName}"
        val resource = resources.findWorkingDirectory(DATASET_FEATURE, key)
            ?: resources.findClasspath(DATASET_FEATURE, key).let { found ->
                require(found.size <= 1) { "Dataset [$key] is declared more than once: ${found.map { it.location }}." }
                found.singleOrNull()
            }
            ?: throw QuerySchemaUnavailableException("Dataset [$key] has no declaration file.")
        return readDataset(resource.location) { parseDatasetDocument(name, resource.readText()) }
    }

    fun loadAll(names: Collection<NamedAggregate>): QueryDatasets = QueryDatasets(names.map(::load))

    // A file that fails to read or validate keeps its error as the cause.
    @Suppress("TooGenericExceptionCaught")
    private inline fun readDataset(location: String, read: () -> QueryDataset): QueryDataset = try {
        read()
    } catch (error: Exception) {
        throw QuerySchemaUnavailableException("Unable to read dataset [$location].", error)
    }

    companion object {
        const val DATASET_FEATURE = "dataset"
    }
}
