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

package me.ahoo.wow.api.caller

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * Who sends a command or runs a query, as the edge authenticated it: the facts admission takes a
 * command's identity from (identity comes from the principal, never from the path or the body), and the
 * facts a query policy restricts a query by. Commands and queries share it: the edge resolves one per request.
 *
 * - [tenantId] is the caller's tenant; a principal without one is in [DEFAULT_TENANT].
 * - [scope] is the caller's owner and space: the scope it acts in.
 * - [subject] is the principal's opaque id, for authorizers, logs and the [Operator] of what it commits; null for an
 *   anonymous caller.
 * - [crossScope] says the caller may act in another tenant or scope by naming it (the `Wow-Scope` header).
 * - [attributes] are further facts about the principal an authorizer or a query policy may read (roles, policies,
 *   `abac.` attributes). They live only as long as the request: none of them is written into a fact,
 *   but for the standard attribute [APP], the calling application, which the edge records as the
 *   command's request fact [me.ahoo.wow.api.decide.RequestFacts.APP_ID].
 * - [service] says the caller is a service identity: another service of the system, authenticated by
 *   its service credential, calling for a saga of its own. Only such a caller may assert the root command a call
 *   acts for (its tenant, scope, operator and causation, the `Wow-Call` header); the edge refuses that header from
 *   any other caller. A service identity has a [subject].
 *
 * A caller is the runtime's view of one request; the [Operator] is what a commit records of it ([toOperator]).
 *
 * Fields may be added: construct one with [of]; the constructor is internal to Wow.
 */
class Caller @InternalWowApi constructor(
    val tenantId: String = DEFAULT_TENANT,
    val scope: Scope = Scope.NONE,
    val subject: String? = null,
    val crossScope: Boolean = false,
    val attributes: Map<String, List<String>> = emptyMap(),
    val service: Boolean = false,
) {
    init {
        require(tenantId.isNotBlank()) { "A caller's tenant is not blank." }
        require(subject == null || subject.isNotBlank()) { "A caller's subject, when present, is not blank." }
        require(!service || subject != null) { "A service identity names its subject." }
    }

    /**
     * The operator a command of this caller records: its [subject] as the id and its own [tenantId];
     * [actingElsewhere] when the command acts in another tenant or scope than the caller's own, through `Wow-Scope`.
     * Null for an anonymous caller.
     */
    fun toOperator(actingElsewhere: Boolean = false): Operator? =
        subject?.let { Operator(it, tenantId, actingElsewhere) }

    /**
     * The application the caller calls from: the first value of its [APP] attribute, which only its
     * resolver sets, from what it authenticated (CoSec's resolver: the application CoSec authorized the request in);
     * null when it has none. ABAC and query policies read it here, and a command records it as its request fact
     * [me.ahoo.wow.api.decide.RequestFacts.APP_ID].
     */
    val app: String? get() = attributes[APP]?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** This Caller with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        tenantId: String = this.tenantId,
        scope: Scope = this.scope,
        subject: String? = this.subject,
        crossScope: Boolean = this.crossScope,
        attributes: Map<String, List<String>> = this.attributes,
        service: Boolean = this.service,
    ): Caller = Caller(tenantId, scope, subject, crossScope, attributes, service)

    override fun equals(other: Any?): Boolean =
        this === other || other is Caller && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Caller(tenantId=$tenantId, scope=$scope, subject=$subject, crossScope=$crossScope, attributes=$attributes, " +
            "service=$service)"

    private fun valueFields(): List<Any?> = listOf(tenantId, scope, subject, crossScope, attributes, service)

    companion object {
        /** The tenant of a principal that has none. */
        const val DEFAULT_TENANT: String = "(0)"

        /** The standard attribute naming the caller's [app]. */
        const val APP: String = "app"

        /** An unauthenticated caller: the default tenant, no scope, no subject. */
        @JvmField
        val ANONYMOUS: Caller = Caller()

        /** A Caller of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            tenantId: String = DEFAULT_TENANT,
            scope: Scope = Scope.NONE,
            subject: String? = null,
            crossScope: Boolean = false,
            attributes: Map<String, List<String>> = emptyMap(),
            service: Boolean = false,
        ): Caller = Caller(tenantId, scope, subject, crossScope, attributes, service)
    }
}
