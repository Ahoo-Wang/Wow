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

package me.ahoo.wow.cosec

import kotlinx.coroutines.reactive.awaitFirstOrNull
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.Caller
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import java.net.URI
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * Authorizes a command with CoSec's policies, for the entries CoSec's WebFilter does not guard (decision D93): a
 * command sent through the gateway by the application itself, a scheduler or a message listener. On the public port
 * the WebFilter has already authorized the request by its path and method, so the host leaves this authorizer off
 * unless `wow.cosec.authorizer.enabled` asks for it.
 *
 * The action is evaluated as the request its command route would be, so one policy guards both entries:
 * `POST /{context}/{aggregate}/{id}/{command-segment}` ([CommandRoutes]), with the attributes [COMMAND]
 * (`context.aggregate.Command`), [AGGREGATE_ID], [TENANT], [OWNER], [SPACE] and [CREATION] for condition matchers.
 * The principal is rebuilt from the [Caller]: its subject, the roles and policies of [CoSecCallerResolver.ROLES] and
 * [CoSecCallerResolver.POLICIES], its other attributes, and its tenant, [defaultTenant] being CoSec's `(0)`. A caller
 * without a subject is CoSec's anonymous principal.
 */
class CoSecCommandAuthorizer(
    private val authorization: CoSecAuthorization,
    private val defaultTenant: String = Caller.DEFAULT_TENANT,
) : CommandAuthorizer {
    override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
        val result = authorization.authorize(requestOf(action), securityContextOf(caller)).awaitFirstOrNull()
        return when {
            result == null -> Authorization.Denied("CoSec gave no decision on ${action.type.name}.")
            result.authorized -> Authorization.Allowed
            else -> Authorization.Denied(result.reason.ifBlank { "CoSec denies ${action.type.name}." })
        }
    }

    /** The security context of [caller], as CoSec would have resolved it for an HTTP request. */
    fun securityContextOf(caller: Caller): SecurityContext {
        val tenant = SimpleTenant(if (caller.tenantId == defaultTenant) Tenant.DEFAULT_TENANT_ID else caller.tenantId)
        val principal: CoSecPrincipal = caller.subject?.let { subject ->
            SimplePrincipal(
                id = subject,
                policies = caller.attributes[CoSecCallerResolver.POLICIES].orEmpty().toSet(),
                roles = caller.attributes[CoSecCallerResolver.ROLES].orEmpty().toSet(),
                attributes = caller.attributes - CoSecCallerResolver.ROLES - CoSecCallerResolver.POLICIES,
            )
        } ?: SimplePrincipal.ANONYMOUS
        return SimpleSecurityContext(SimpleTenantPrincipal(principal, tenant), tenant)
    }

    companion object {
        const val COMMAND: String = "wow.command"
        const val AGGREGATE_ID: String = "wow.aggregate.id"
        const val TENANT: String = "wow.tenant"
        const val OWNER: String = "wow.owner"
        const val SPACE: String = "wow.space"
        const val CREATION: String = "wow.creation"

        /** The request [action]'s command route would be. */
        @JvmStatic
        fun requestOf(action: CommandAction): Request {
            val type = action.ref.type
            val attributes = buildMap {
                put(COMMAND, "${type.context}.${type.name}.${action.type.name}")
                put(AGGREGATE_ID, action.ref.id)
                put(TENANT, action.ref.tenantId)
                action.scope.ownerId?.let { put(OWNER, it) }
                action.scope.spaceId?.let { put(SPACE, it) }
                put(CREATION, action.creation.name)
            }
            val path = "/${type.context}/${type.name}/${action.ref.id}/${CommandRoutes.segment(action.type.name)}"
            return ActionRequest(path, attributes)
        }
    }
}

/** A command action as the request of its route: no header, no query, no cookie, no remote address. */
private data class ActionRequest(override val path: String, override val attributes: Map<String, String>) : Request {
    override val method: String
        get() = CommandRoutes.POST
    override val remoteIp: String
        get() = ""
    override val origin: URI
        get() = NO_URI
    override val referer: URI
        get() = NO_URI

    override fun getHeader(key: String): String = ""

    override fun getQuery(key: String): String = ""

    override fun getCookieValue(key: String): String = ""

    override fun withAttributes(attributes: Map<String, String>): Request = copy(attributes = attributes)

    private companion object {
        val NO_URI: URI = URI.create("")
    }
}
