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

package me.ahoo.wow.spring.boot

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryBackendFactory
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryTenantPolicy
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.index.QueryIndexDeclaration
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.runtime.admission.TenantPolicy
import org.springframework.context.SmartLifecycle

/**
 * The query tenant policy over the host's [TenantPolicy] (decision D116): a query of an aggregate type whose tenant
 * `wow.aggregates.[<context>.<aggregate>].tenant` fixes is restricted to that tenant, any other to its caller's, as a
 * command or a state read of it is.
 */
class HostQueryTenants(private val tenants: TenantPolicy) : QueryTenantPolicy {
    override fun tenantOf(type: AggregateType, caller: QueryCaller): String? =
        tenants.fixedTenant(type) ?: caller.tenantId
}

/**
 * The snapshot query backend of the node (decision D110): the [factory] of each aggregate type's backend and storage
 * facts (null when no backend serves the node's snapshots), and, for a backend the host manages, how it [prepare]s an aggregate's storage and [manages][manageIndexes]
 * its `x-wow-index` indexes at startup (decision D151).
 */
class SnapshotQueryBackend(
    val factory: QueryBackendFactory<*>?,
    private val prepare: (AggregateType) -> Unit = {},
    private val indexes: ((AggregateType, QueryModelSchema, List<QueryIndexDeclaration>) -> QueryIndexReport)? = null,
) {
    /** Whether the host manages this backend's indexes; an application's own backend manages its own. */
    val managesIndexes: Boolean get() = factory != null && indexes != null

    /** Makes sure [type]'s storage exists, so its facts are the storage's own. */
    fun prepare(type: AggregateType) = prepare.invoke(type)

    /** Manages [declarations], [type]'s indexes on its query model [schema], as `wow.query.index.ddl` says. */
    fun manageIndexes(
        type: AggregateType,
        schema: QueryModelSchema,
        declarations: List<QueryIndexDeclaration>,
    ): QueryIndexReport = indexes?.invoke(type, schema, declarations) ?: QueryIndexReport()

    companion object {
        /** No backend: the node's snapshots are not queried. */
        @JvmField
        val NONE: SnapshotQueryBackend = SnapshotQueryBackend(null)
    }
}

/** What managing one aggregate's indexes did: the indexes it created, and those it found missing or broken. */
data class QueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
)

/**
 * The aggregate types of [manifest] in [contexts] whose snapshots are queried: the Manifest lists their
 * [snapshot model][QueryRoutes.SNAPSHOT] (decision D128).
 */
internal fun snapshotTypes(manifest: Manifest, contexts: Set<String>): List<AggregateType> =
    queryTypes(manifest, contexts, QueryRoutes.SNAPSHOT)

/** The aggregate types of [manifest] in [contexts] whose [model] the Manifest lists. */
internal fun queryTypes(manifest: Manifest, contexts: Set<String>, model: String): List<AggregateType> =
    manifest.contexts.filter { it.name in contexts }.flatMap { context ->
        context.aggregates.filter { aggregate -> aggregate.queries.any { it.model == model } }
            .map { AggregateType(context.name, it.name) }
    }

/**
 * Manages the indexes of every aggregate type in [types] at startup (decision D151), after the backends' schemas
 * exist ([BackendsLifecycle]) and before the node starts: each aggregate's storage is prepared, its query model
 * compiled from [catalog], and its Manifest's `x-wow-index` planned and managed by [backend] as [ddl] says. A key
 * the model cannot index fails the start.
 */
class QueryIndexesLifecycle(
    private val manifest: Manifest,
    private val types: List<AggregateType>,
    private val backend: SnapshotQueryBackend,
    private val catalog: QuerySchemaCatalog,
    private val ddl: QueryIndexDdl,
) : SmartLifecycle {
    @Volatile
    private var running = false

    /** What the last start did, by aggregate type. */
    @Volatile
    var reports: Map<AggregateType, QueryIndexReport> = emptyMap()
        private set

    override fun start() {
        if (ddl != QueryIndexDdl.NONE && backend.managesIndexes) {
            reports = types.associateWith { type ->
                backend.prepare(type)
                val schema = runBlocking { catalog.schema(type, QueryModel.SNAPSHOT) }
                val declarations = QueryIndexDeclaration.of(manifest, type.context, type.name)
                backend.manageIndexes(type, schema, declarations).also { report ->
                    if (report.created.isNotEmpty()) {
                        logger.info { "Created the query indexes ${report.created} of $type." }
                    }
                }
            }
        }
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = WowLifecycles.QUERY_INDEXES_PHASE

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
