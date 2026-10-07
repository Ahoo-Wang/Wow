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

package me.ahoo.wow.webflux.route.identity

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.identity.IdentityFact
import me.ahoo.wow.identity.IdentityResolver
import me.ahoo.wow.identity.IdentitySource
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.openapi.CommonComponent
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import org.springframework.web.reactive.function.server.ServerRequest
import java.util.concurrent.ConcurrentHashMap

/** Where a route takes one identity fact from. */
@InternalWowApi
enum class RouteIdentitySource {
    /** The aggregate's static tenant. */
    STATIC,

    /** A path variable the route declares. */
    PATH,

    /** Request headers, in order. */
    HEADER,

    /** The owner (an aggregate whose owner is its ID, `OwnerPolicy.AGGREGATE_ID`), else a header. */
    OWNER,

    /** Not taken from the request. */
    NONE,
}

/**
 * How a route takes one identity fact.
 *
 * @property source where the value comes from.
 * @property value the static value ([STATIC][RouteIdentitySource.STATIC]) or the path variable
 * ([PATH][RouteIdentitySource.PATH]).
 * @property headers the headers read in order ([HEADER][RouteIdentitySource.HEADER], and the fallback of
 * [OWNER][RouteIdentitySource.OWNER]); for a fact the route fixes ([STATIC][RouteIdentitySource.STATIC],
 * [PATH][RouteIdentitySource.PATH]), the header that must not contradict it (none for a static tenant: as in 9.2, a
 * tenant header is ignored there).
 * @property derived whether the value is derived from another fact: the owner of an aggregate owned by its ID, taken
 * from `{id}`. It decides a command's owner, and a header may not contradict it, but a read does not filter by it.
 */
@InternalWowApi
data class FactBinding(
    val source: RouteIdentitySource,
    val value: String? = null,
    val headers: List<String> = emptyList(),
    val derived: Boolean = false,
) {
    @InternalWowApi
    companion object {
        val NONE = FactBinding(RouteIdentitySource.NONE)
    }
}

/**
 * Where one route takes each identity fact from, decided once from the route contract when the router is
 * materialized: the path variables the contract declares, the aggregate's static tenant, ownership policy and space,
 * and the [header aliases][IdentityHeaderAliases]. Each value is then decided by [IdentityResolver], so the route,
 * not each handler, states which source is authoritative:
 *
 * | Fact | Source |
 * |---|---|
 * | tenant | static tenant (the header is ignored) → `{tenantId}` → `Command-Tenant-Id` |
 * | owner | `{ownerId}` → `{id}` when the owner is the aggregate ID → `Command-Owner-Id` |
 * | aggregate ID | owner is the aggregate ID: `{ownerId}` → `{id}` → owner header → `Command-Aggregate-Id`; otherwise `{id}` → `Command-Aggregate-Id` |
 * | space | spaced aggregate only: `Wow-Space-Id` → space aliases |
 * | request ID | `Command-Request-Id` → request ID aliases |
 *
 * A declared path variable is authoritative: blank is rejected (400), and a header (or a command body, see
 * [RequestIdentity]) contradicting a tenant or owner the path fixes is rejected (400, V3). A command body contradicting
 * the static tenant is rejected too; a tenant header there is ignored, as before 9.3.0.
 */
@InternalWowApi
data class RouteIdentityBinding(
    val pathVariables: Set<String>,
    val tenantId: FactBinding,
    val ownerId: FactBinding,
    val aggregateId: FactBinding,
    val spaceId: FactBinding,
    val requestId: FactBinding,
    /** The space headers, read by the query scope, which applies its own space policy. */
    val spaceHeaders: List<String>,
) {
    private val pathVariableNames: Array<String> = pathVariables.toTypedArray()

    /** Rejects a blank value of any identity path variable the route declares. */
    fun requirePathVariables(request: ServerRequest) {
        for (variable in pathVariableNames) {
            request.requirePathVariable(variable)
        }
    }

    fun tenantId(request: ServerRequest, body: String? = null): String? =
        resolve(IdentityFact.TENANT_ID, tenantId, request, body)

    /**
     * The owner. A blank [body] states no owner when the owner is [derived][FactBinding.derived] from `{id}` (as in
     * 9.2, the command's owner is then its aggregate ID); against an owner the route states itself it is a conflict.
     */
    fun ownerId(request: ServerRequest, body: String? = null): String? {
        val bodyOwner = if (ownerId.derived && body.isNullOrBlank()) null else body
        return resolve(IdentityFact.OWNER_ID, ownerId, request, bodyOwner)
    }

    /**
     * The owner a read filters by: [ownerId], except that an owner [derived][FactBinding.derived] from `{id}` is not
     * used (the aggregate ID already pins the row, and an aggregate created in-process may store a blank owner); a
     * `Command-Owner-Id` that agrees with it still applies, as in 9.2.
     */
    fun readOwnerId(request: ServerRequest): String? {
        val ownerId = ownerId(request)
        if (this.ownerId.derived) {
            return request.firstHeader(this.ownerId.headers)
        }
        return ownerId
    }

    fun aggregateId(request: ServerRequest): String? {
        if (aggregateId.source == RouteIdentitySource.OWNER) {
            return aggregateIdFromOwner(request, ownerId(request))
        }
        return resolve(IdentityFact.AGGREGATE_ID, aggregateId, request, null)
    }

    /** Whether the aggregate ID is the owner ([ownerId]) when there is one. */
    internal val aggregateIdIsOwner: Boolean
        get() = aggregateId.source == RouteIdentitySource.OWNER

    /** The aggregate ID of a route whose aggregate ID is the owner, given that resolved [ownerId]. */
    internal fun aggregateIdFromOwner(request: ServerRequest, ownerId: String?): String? =
        ownerId ?: request.firstHeader(aggregateId.headers)

    fun spaceId(request: ServerRequest): String? = resolve(IdentityFact.SPACE_ID, spaceId, request, null)

    fun spaceIdHeader(request: ServerRequest): String? = request.firstHeader(spaceHeaders)

    fun requestId(request: ServerRequest): String? = request.firstHeader(requestId.headers)

    private fun resolve(fact: IdentityFact, binding: FactBinding, request: ServerRequest, body: String?): String? {
        val route = when (binding.source) {
            // One non-authoritative source: nothing can conflict, a body included.
            RouteIdentitySource.HEADER -> return request.firstHeader(binding.headers)
            RouteIdentitySource.OWNER, RouteIdentitySource.NONE -> return null
            RouteIdentitySource.STATIC -> {
                if (body == null && binding.headers.isEmpty()) {
                    return binding.value
                }
                requireNotNull(binding.value)
            }

            RouteIdentitySource.PATH -> request.requirePathVariable(requireNotNull(binding.value))
        }
        val header = request.firstHeader(binding.headers)
        val value = IdentityResolver.resolve(fact, IdentitySource.ROUTE, route, IdentitySource.HEADER, header)
        if (body != null) {
            // The body comes first for the command it builds; here it may only not contradict what the route fixes.
            IdentityResolver.resolve(
                fact,
                IdentitySource.ROUTE,
                route,
                IdentitySource.HEADER,
                header,
                IdentitySource.BODY,
                body
            )
        }
        return value
    }

    @InternalWowApi
    companion object {
        /** The identity path variables a route may declare. */
        val IDENTITY_PATH_VARIABLES: Set<String> =
            setOf(MessageRecords.TENANT_ID, MessageRecords.OWNER_ID, MessageRecords.ID)

        private val TENANT_HEADERS = listOf(CommandComponent.Header.TENANT_ID)
        private val OWNER_HEADERS = listOf(CommandComponent.Header.OWNER_ID)
        private val AGGREGATE_ID_HEADERS = listOf(CommandComponent.Header.AGGREGATE_ID)
        private val FROM_OWNER = FactBinding(RouteIdentitySource.OWNER, headers = AGGREGATE_ID_HEADERS)

        fun of(
            pathVariables: Set<String>,
            staticTenantId: String?,
            ownerPolicy: OwnerPolicy,
            spaced: Boolean,
            aliases: IdentityHeaderAliases = IdentityHeaderAliases.NONE,
        ): RouteIdentityBinding {
            val identityPathVariables = pathVariables.intersect(IDENTITY_PATH_VARIABLES)
            val ownerIsAggregateId = ownerPolicy == OwnerPolicy.AGGREGATE_ID
            val spaceHeaders = listOf(CommonComponent.Header.SPACE_ID) + aliases.spaceId
            return RouteIdentityBinding(
                pathVariables = identityPathVariables,
                tenantId = tenantBinding(identityPathVariables, staticTenantId),
                ownerId = ownerBinding(identityPathVariables, ownerIsAggregateId),
                aggregateId = aggregateIdBinding(identityPathVariables, ownerIsAggregateId),
                spaceId = if (spaced) spaceHeaders.headerBinding() else FactBinding.NONE,
                requestId = FactBinding(
                    RouteIdentitySource.HEADER,
                    headers = listOf(CommandComponent.Header.REQUEST_ID) + aliases.requestId
                ),
                spaceHeaders = spaceHeaders,
            )
        }

        private fun List<String>.headerBinding(): FactBinding = FactBinding(RouteIdentitySource.HEADER, headers = this)

        private fun tenantBinding(pathVariables: Set<String>, staticTenantId: String?): FactBinding = when {
            // A tenant header is ignored here, as in 9.2; a contradicting command body is still rejected.
            !staticTenantId.isNullOrBlank() -> FactBinding(RouteIdentitySource.STATIC, staticTenantId)
            MessageRecords.TENANT_ID in pathVariables ->
                FactBinding(RouteIdentitySource.PATH, MessageRecords.TENANT_ID, TENANT_HEADERS)

            else -> FactBinding(RouteIdentitySource.HEADER, headers = TENANT_HEADERS)
        }

        private fun ownerBinding(pathVariables: Set<String>, ownerIsAggregateId: Boolean): FactBinding = when {
            MessageRecords.OWNER_ID in pathVariables ->
                FactBinding(RouteIdentitySource.PATH, MessageRecords.OWNER_ID, OWNER_HEADERS)
            // The owner is the aggregate ID, which the path states.
            ownerIsAggregateId && MessageRecords.ID in pathVariables ->
                FactBinding(RouteIdentitySource.PATH, MessageRecords.ID, OWNER_HEADERS, derived = true)

            else -> FactBinding(RouteIdentitySource.HEADER, headers = OWNER_HEADERS)
        }

        private fun aggregateIdBinding(pathVariables: Set<String>, ownerIsAggregateId: Boolean): FactBinding = when {
            ownerIsAggregateId && MessageRecords.OWNER_ID in pathVariables -> FROM_OWNER
            MessageRecords.ID in pathVariables -> FactBinding(RouteIdentitySource.PATH, MessageRecords.ID)
            ownerIsAggregateId -> FROM_OWNER
            else -> FactBinding(RouteIdentitySource.HEADER, headers = AGGREGATE_ID_HEADERS)
        }

        fun of(
            pathVariables: Set<String>,
            aggregateRouteMetadata: AggregateRouteMetadata<*>,
            aliases: IdentityHeaderAliases = IdentityHeaderAliases.NONE,
        ): RouteIdentityBinding = of(
            pathVariables = pathVariables,
            staticTenantId = aggregateRouteMetadata.aggregateMetadata.staticTenantId,
            ownerPolicy = aggregateRouteMetadata.ownerPolicy,
            spaced = aggregateRouteMetadata.spaced,
            aliases = aliases,
        )

        fun of(
            pathVariables: Set<String>,
            aggregateMetadata: AggregateMetadata<*, *>,
            aliases: IdentityHeaderAliases = IdentityHeaderAliases.NONE,
        ): RouteIdentityBinding = of(
            pathVariables = pathVariables,
            staticTenantId = aggregateMetadata.staticTenantId,
            ownerPolicy = aggregateMetadata.owner,
            spaced = aggregateMetadata.spaced,
            aliases = aliases,
        )
    }
}

/**
 * The identity side of one materialized route: its declared identity path variables, the header aliases, and, for a
 * route of one aggregate, that aggregate's [RouteIdentityBinding], computed once. Carried to the handlers as a request
 * attribute; a handler of another aggregate (the command facade) gets a binding computed from the same path variables.
 */
@InternalWowApi
class RouteIdentity(
    val pathVariables: Set<String>,
    val aliases: IdentityHeaderAliases = IdentityHeaderAliases.NONE,
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>? = null,
) {
    private val routeBinding: RouteIdentityBinding? = aggregateRouteMetadata?.let {
        RouteIdentityBinding.of(pathVariables, it, aliases)
    }

    /**
     * Bindings for other aggregates (the command facade), one per aggregate policy, computed once: by static tenant
     * (`""` for none), then by owner policy and space, so that finding one allocates nothing. A benign race: a binding
     * is immutable, and two threads computing the same one is harmless.
     */
    private val bindings = ConcurrentHashMap<String, Array<RouteIdentityBinding?>>()

    fun binding(aggregateRouteMetadata: AggregateRouteMetadata<*>): RouteIdentityBinding {
        if (routeBinding != null && this.aggregateRouteMetadata === aggregateRouteMetadata) {
            return routeBinding
        }
        return binding(
            aggregateRouteMetadata.aggregateMetadata.staticTenantId,
            aggregateRouteMetadata.ownerPolicy,
            aggregateRouteMetadata.spaced
        )
    }

    fun binding(aggregateMetadata: AggregateMetadata<*, *>): RouteIdentityBinding {
        val routeAggregate = this.aggregateRouteMetadata?.aggregateMetadata
        if (routeBinding != null && (routeAggregate === aggregateMetadata || routeAggregate == aggregateMetadata)) {
            return routeBinding
        }
        return binding(aggregateMetadata.staticTenantId, aggregateMetadata.owner, aggregateMetadata.spaced)
    }

    private fun binding(staticTenantId: String?, ownerPolicy: OwnerPolicy, spaced: Boolean): RouteIdentityBinding {
        // `RouteIdentityBinding.of` treats a blank static tenant as none.
        val tenant = if (staticTenantId.isNullOrBlank()) NO_STATIC_TENANT else staticTenantId
        val table = bindings[tenant] ?: bindings.computeIfAbsent(tenant) { arrayOfNulls(OWNER_POLICIES.size * 2) }
        val index = ownerPolicy.ordinal * 2 + if (spaced) 1 else 0
        return table[index] ?: RouteIdentityBinding.of(
            pathVariables = pathVariables,
            staticTenantId = tenant.ifEmpty { null },
            ownerPolicy = ownerPolicy,
            spaced = spaced,
            aliases = aliases
        ).also { table[index] = it }
    }

    @InternalWowApi
    companion object {
        val ATTRIBUTE: String = RouteIdentity::class.java.name

        fun of(contract: HttpRouteContract, aliases: IdentityHeaderAliases = IdentityHeaderAliases.NONE): RouteIdentity {
            val pathVariables = contract.parameters
                .filter { it.location == HttpParameterLocation.PATH }
                .map { it.name }
                .toSet()
                .intersect(RouteIdentityBinding.IDENTITY_PATH_VARIABLES)
            val aggregateRouteMetadata = when (val metadata = contract.handlerMetadata) {
                is HttpRouteHandlerMetadata.Aggregate -> metadata.aggregateRouteMetadata
                is HttpRouteHandlerMetadata.Command -> metadata.aggregateRouteMetadata
                HttpRouteHandlerMetadata.None -> null
            }
            return RouteIdentity(pathVariables, aliases, aggregateRouteMetadata)
        }

        /**
         * The route identity of [request]: the one its route was materialized with, else (a handler invoked outside a
         * materialized router) one read from the path variables the request matched.
         */
        fun of(request: ServerRequest): RouteIdentity =
            request.attributes()[ATTRIBUTE] as? RouteIdentity ?: unrouted(request, IdentityHeaderAliases.NONE)

        private val OWNER_POLICIES = OwnerPolicy.entries
        private const val NO_STATIC_TENANT = ""

        /** The identity path variables, each a bit of the index into an [unrouted] table. */
        private val INDEXED_PATH_VARIABLES = RouteIdentityBinding.IDENTITY_PATH_VARIABLES.toTypedArray()

        /**
         * Route identities for handlers invoked outside the router, by aliases and then by the identity path variables
         * the request matched (a bit set over [INDEXED_PATH_VARIABLES]): a handful, found without allocating.
         */
        private val UNROUTED = ConcurrentHashMap<IdentityHeaderAliases, Array<RouteIdentity?>>()

        private fun unrouted(request: ServerRequest, aliases: IdentityHeaderAliases): RouteIdentity {
            val requestPathVariables = request.pathVariables()
            var index = 0
            for (bit in INDEXED_PATH_VARIABLES.indices) {
                if (requestPathVariables.containsKey(INDEXED_PATH_VARIABLES[bit])) {
                    index = index or (1 shl bit)
                }
            }
            val table = UNROUTED[aliases]
                ?: UNROUTED.computeIfAbsent(aliases) { arrayOfNulls(1 shl INDEXED_PATH_VARIABLES.size) }
            return table[index] ?: RouteIdentity(pathVariablesOf(index), aliases).also { table[index] = it }
        }

        private fun pathVariablesOf(index: Int): Set<String> =
            INDEXED_PATH_VARIABLES.filterIndexedTo(LinkedHashSet()) { bit, _ -> index and (1 shl bit) != 0 }

        /**
         * Gives [request] a route identity with [aliases] when its handler was invoked outside a materialized router
         * (a downstream module calling a command handler directly, say), so the header aliases still apply; a request
         * routed by the router keeps the identity its route was materialized with.
         */
        fun withAliases(request: ServerRequest, aliases: IdentityHeaderAliases) {
            if (aliases.isEmpty() || request.attributes().containsKey(ATTRIBUTE)) {
                return
            }
            request.attributes()[ATTRIBUTE] = unrouted(request, aliases)
        }
    }
}

/**
 * The identity of one request to one aggregate's route: each fact (tenant, owner, aggregate ID, space, request ID) read
 * where the route states it, by the same rules as the built-in command and query handlers (see
 * [RouteIdentityBinding]), header aliases included. A custom handler reads identity through it, with
 * [ServerRequest.identity].
 *
 * Creating it rejects a blank identity path variable the route declares (400), before any fact is read, so a blank
 * segment is reported as such whatever else the request contradicts. A header contradicting a tenant or owner the
 * path fixes is rejected (400) when that fact is read.
 */
class RequestIdentity internal constructor(
    val request: ServerRequest,
    private val binding: RouteIdentityBinding
) {
    init {
        binding.requirePathVariables(request)
    }

    /**
     * The tenant: the aggregate's static tenant, else `{tenantId}`, else `Command-Tenant-Id`. A non-null [body] (the
     * command body's tenant) is only checked: it may not contradict a tenant the route fixes.
     */
    fun tenantId(body: String? = null): String? = binding.tenantId(request, body)

    /**
     * The owner: `{ownerId}`, else `{id}` when the owner is the aggregate ID, else `Command-Owner-Id`. A non-null
     * [body] is only checked against an owner the route fixes.
     */
    fun ownerId(body: String? = null): String? {
        val resolved = resolvedOwnerId
        if (body == null && resolved !== UNRESOLVED) {
            return resolved as String?
        }
        // The value never depends on body, which is only checked; keep it for aggregateId().
        return binding.ownerId(request, body).also { resolvedOwnerId = it }
    }

    /** The owner once resolved, else [UNRESOLVED]: the aggregate ID of an aggregate owned by its ID reads it again. */
    private var resolvedOwnerId: Any? = UNRESOLVED

    /** The owner a read filters by: [ownerId], except that an owner derived from `{id}` is not a filter. */
    fun readOwnerId(): String? = binding.readOwnerId(request)

    /** The aggregate ID: from the owner when the owner is the aggregate ID, else `{id}`, else `Command-Aggregate-Id`. */
    fun aggregateId(): String? {
        if (binding.aggregateIdIsOwner) {
            return binding.aggregateIdFromOwner(request, ownerId())
        }
        return binding.aggregateId(request)
    }

    /** The space: `Wow-Space-Id` (or a space alias) when the aggregate is spaced, otherwise `null`. */
    fun spaceId(): String? = binding.spaceId(request)

    /** The request ID: `Command-Request-Id` or a request ID alias; a blank header counts as absent. */
    fun requestId(): String? = binding.requestId(request)

    /** The `Wow-Space-Id` header (or a space alias), whatever the aggregate's space policy. */
    internal fun spaceIdHeader(): String? = binding.spaceIdHeader(request)

    private companion object {
        val UNRESOLVED = Any()
    }
}

/** The identity this request states for the aggregate of [aggregateRouteMetadata]. */
fun ServerRequest.identity(aggregateRouteMetadata: AggregateRouteMetadata<*>): RequestIdentity =
    RequestIdentity(this, RouteIdentity.of(this).binding(aggregateRouteMetadata))

/** The identity this request states for the aggregate of [aggregateMetadata]. */
fun ServerRequest.identity(aggregateMetadata: AggregateMetadata<*, *>): RequestIdentity =
    RequestIdentity(this, RouteIdentity.of(this).binding(aggregateMetadata))

/** The identity of a request to a route of an aggregate with the [owner] policy and no static tenant or space. */
internal fun ServerRequest.identity(owner: OwnerPolicy): RequestIdentity {
    val routeIdentity = RouteIdentity.of(this)
    return RequestIdentity(
        this,
        RouteIdentityBinding.of(
            routeIdentity.pathVariables,
            null,
            owner,
            spaced = false,
            aliases = routeIdentity.aliases
        )
    )
}

/**
 * The value of the path variable [variable]. A route that declares the variable states the value in its path, and a
 * gateway may authorize on that segment, so a blank segment (such as `%20`) is rejected with an
 * [IllegalArgumentException] (`IllegalArgument`, 400) and no header is read in its place.
 */
internal fun ServerRequest.requirePathVariable(variable: String): String {
    val value = pathVariables()[variable]
    require(!value.isNullOrBlank()) {
        "Path variable [$variable] must not be blank."
    }
    return value
}

internal fun ServerRequest.firstHeader(names: List<String>): String? {
    if (names.isEmpty()) {
        return null
    }
    val headers = headers()
    for (index in names.indices) {
        val value = headers.firstHeader(names[index])
        if (!value.isNullOrBlank()) {
            return value
        }
    }
    return null
}
