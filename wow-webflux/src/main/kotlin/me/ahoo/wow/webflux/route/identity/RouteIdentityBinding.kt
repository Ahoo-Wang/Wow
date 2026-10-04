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
import me.ahoo.wow.identity.IdentityHint
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
 * [PATH][RouteIdentitySource.PATH]), the header that must not contradict it.
 */
@InternalWowApi
data class FactBinding(
    val source: RouteIdentitySource,
    val value: String? = null,
    val headers: List<String> = emptyList(),
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
 * | tenant | static tenant → `{tenantId}` → `Command-Tenant-Id` |
 * | owner | `{ownerId}` → `{id}` when the owner is the aggregate ID → `Command-Owner-Id` |
 * | aggregate ID | owner is the aggregate ID: `{ownerId}` → `{id}` → owner header → `Command-Aggregate-Id`; otherwise `{id}` → `Command-Aggregate-Id` |
 * | space | spaced aggregate only: `Wow-Space-Id` → space aliases |
 * | request ID | `Command-Request-Id` → request ID aliases |
 *
 * A declared path variable is authoritative: blank is rejected (400), and a header (or a command body, see
 * [RequestIdentity]) contradicting a tenant or owner the route fixes is rejected (400, V3).
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
    /** Rejects a blank value of any identity path variable the route declares. */
    fun requirePathVariables(request: ServerRequest) {
        pathVariables.forEach { request.requirePathVariable(it) }
    }

    fun tenantId(request: ServerRequest, body: String? = null): String? =
        resolve(IdentityFact.TENANT_ID, tenantId, request, body)

    fun ownerId(request: ServerRequest, body: String? = null): String? =
        resolve(IdentityFact.OWNER_ID, ownerId, request, body)

    fun aggregateId(request: ServerRequest): String? {
        if (aggregateId.source == RouteIdentitySource.OWNER) {
            return ownerId(request) ?: request.firstHeader(aggregateId.headers)
        }
        return resolve(IdentityFact.AGGREGATE_ID, aggregateId, request, null)
    }

    fun spaceId(request: ServerRequest): String? = resolve(IdentityFact.SPACE_ID, spaceId, request, null)

    fun spaceIdHeader(request: ServerRequest): String? = request.firstHeader(spaceHeaders)

    fun requestId(request: ServerRequest): String? = request.firstHeader(requestId.headers)

    private fun resolve(fact: IdentityFact, binding: FactBinding, request: ServerRequest, body: String?): String? {
        val hints = when (binding.source) {
            RouteIdentitySource.STATIC -> listOf(
                IdentityHint(IdentitySource.ROUTE, requireNotNull(binding.value)),
                IdentityHint.of(IdentitySource.HEADER, request.firstHeader(binding.headers)),
            )

            RouteIdentitySource.PATH -> listOf(
                IdentityHint(IdentitySource.ROUTE, request.requirePathVariable(requireNotNull(binding.value))),
                IdentityHint.of(IdentitySource.HEADER, request.firstHeader(binding.headers)),
            )

            RouteIdentitySource.HEADER -> listOf(
                IdentityHint.of(IdentitySource.HEADER, request.firstHeader(binding.headers))
            )

            RouteIdentitySource.OWNER, RouteIdentitySource.NONE -> emptyList()
        }
        val value = IdentityResolver.resolve(fact, hints)
        if (body != null) {
            // The body comes first for the command it builds; here it may only not contradict what the route fixes.
            IdentityResolver.resolve(fact, hints + IdentityHint(IdentitySource.BODY, body))
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
            !staticTenantId.isNullOrBlank() -> FactBinding(RouteIdentitySource.STATIC, staticTenantId, TENANT_HEADERS)
            MessageRecords.TENANT_ID in pathVariables ->
                FactBinding(RouteIdentitySource.PATH, MessageRecords.TENANT_ID, TENANT_HEADERS)

            else -> FactBinding(RouteIdentitySource.HEADER, headers = TENANT_HEADERS)
        }

        private fun ownerBinding(pathVariables: Set<String>, ownerIsAggregateId: Boolean): FactBinding = when {
            MessageRecords.OWNER_ID in pathVariables ->
                FactBinding(RouteIdentitySource.PATH, MessageRecords.OWNER_ID, OWNER_HEADERS)
            // The owner is the aggregate ID, which the path states.
            ownerIsAggregateId && MessageRecords.ID in pathVariables ->
                FactBinding(RouteIdentitySource.PATH, MessageRecords.ID, OWNER_HEADERS)

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

    fun binding(aggregateRouteMetadata: AggregateRouteMetadata<*>): RouteIdentityBinding {
        if (routeBinding != null && this.aggregateRouteMetadata === aggregateRouteMetadata) {
            return routeBinding
        }
        return RouteIdentityBinding.of(pathVariables, aggregateRouteMetadata, aliases)
    }

    fun binding(aggregateMetadata: AggregateMetadata<*, *>): RouteIdentityBinding {
        if (routeBinding != null && this.aggregateRouteMetadata?.aggregateMetadata == aggregateMetadata) {
            return routeBinding
        }
        return RouteIdentityBinding.of(pathVariables, aggregateMetadata, aliases)
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
            request.attribute(ATTRIBUTE).orElse(null) as? RouteIdentity
                ?: RouteIdentity(request.pathVariables().keys.intersect(RouteIdentityBinding.IDENTITY_PATH_VARIABLES))
    }
}

/**
 * The identity of one request to one aggregate's route. Creating it rejects a blank identity path variable the route
 * declares, before any fact is read, so a blank segment is reported as such whatever else the request contradicts.
 */
@InternalWowApi
class RequestIdentity(val request: ServerRequest, val binding: RouteIdentityBinding) {
    init {
        binding.requirePathVariables(request)
    }

    fun tenantId(body: String? = null): String? = binding.tenantId(request, body)

    fun ownerId(body: String? = null): String? = binding.ownerId(request, body)

    fun aggregateId(): String? = binding.aggregateId(request)

    fun spaceId(): String? = binding.spaceId(request)

    fun requestId(): String? = binding.requestId(request)
}

@InternalWowApi
fun ServerRequest.identity(aggregateRouteMetadata: AggregateRouteMetadata<*>): RequestIdentity =
    RequestIdentity(this, RouteIdentity.of(this).binding(aggregateRouteMetadata))

@InternalWowApi
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
    for (name in names) {
        val value = headers().firstHeader(name)
        if (!value.isNullOrBlank()) {
            return value
        }
    }
    return null
}
