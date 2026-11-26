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

package me.ahoo.wow.api.client

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.schedule.Schedules
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/**
 * One command a [CommandClient] sends, and how: the command object, the aggregate it goes to, the scope it acts in and
 * what the sender waits for.
 *
 * - [command]: the command object, sent as it is (not a [Command], whose identity the client gives it).
 * - [aggregateId]: the aggregate's id; null when the command names no aggregate id: a creation, whose id the server assigns,
 *   or an `@Unaddressed` command, whose transformer finds its aggregate. The command's model on the server decides
 *   which, never the client.
 * - [type]: the aggregate type; null to derive it from the command's class, from the models of the service's own
 *   contexts and the contracts of its peers. A class no aggregate known to the client decides, or several decide, fails
 *   when it is sent, as does a lifecycle command (`DeleteAggregate`, ...), which every aggregate takes:
 *   `DeleteAggregate().toCommandRequest(id) { type(ShopContract.Cart.TYPE) }`.
 * - [targetScope]: the tenant and the scope a cross-scope caller acts in (`Wow-Scope`): its tenant, else the caller's,
 *   and its scope, else the caller's own ([TargetScope.scope] null; a saga's command keeps the commit's), on every
 *   path alike. It is the only source of the tenant and scope: a target tenant that is the caller's own changes
 *   nothing.
 * - [wait] (`Wow-Wait`; the server's default plan when null) within [timeout] when given (`Wow-Wait-Timeout`).
 * - [requestId], which makes the command idempotent (`Wow-Request-Id`). When null, each send takes a new one, which the
 *   client's own retries keep; a request sent again by the application is then another request. For an
 *   application-level retry, set [requestId] (or keep the one the first result reports) and send that request again.
 * - [expectedVersion] of the aggregate (`If-Match`; 0 for one never written).
 *
 * Build it with [toCommandRequest] (`PayOrder(amount).toCommandRequest(orderId) { wait(WaitPlan.COMMITTED) }`), which
 * the entries that take a command build it with (`context.call(command, id) { ... }`, `client.wait(command, id)`), or with
 * [of], [builder] or [to]; the constructor is internal to Wow. Fields may be added.
 */
class CommandRequest<C : Any> @InternalWowApi constructor(
    val command: C,
    val aggregateId: String? = null,
    val type: AggregateType? = null,
    val targetScope: TargetScope? = null,
    val wait: WaitPlan? = null,
    val timeout: Duration? = null,
    val requestId: String? = null,
    val expectedVersion: Int? = null,
) {
    init {
        require(command !is CommandRequest<*>) {
            "A CommandRequest sends a command object, not another request: pass a request that is built as it is " +
                "(context.call(request), client.wait(request)), and change its fields with request.toBuilder(), " +
                "not in a block."
        }
        require(command !is Command<*>) {
            "A CommandRequest sends the command object, not a Command: its id and scope are the request's."
        }
        require(command.javaClass !in Schedules.COMMANDS.values) {
            "${command.javaClass.simpleName} is a schedule command the kernel decides: a decide function schedules " +
                "with DecideScope.schedule."
        }
        require(aggregateId == null || aggregateId.isNotBlank()) { "An aggregate id, when given, is not blank." }
        require(timeout == null || timeout.isPositive()) { "A wait timeout is positive." }
        require(requestId == null || requestId.isNotBlank()) { "A request id, when given, is not blank." }
        require(expectedVersion == null || expectedVersion >= 0) { "An expected version is never negative." }
    }

    /** The plan the command waits with: [wait] (or the default plan when only a [timeout] is given) within [timeout]. */
    val plan: WaitPlan?
        get() = when {
            timeout == null -> wait
            else -> (wait ?: WaitPlan.DEFAULT).within(timeout)
        }

    /** A builder of this request, to change some of its fields: the others, [requestId] included, are kept. */
    fun toBuilder(): Builder<C> = Builder(command).aggregateId(aggregateId).type(type).targetScope(targetScope)
        .wait(wait).timeout(timeout).requestId(requestId).expectedVersion(expectedVersion)

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandRequest<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandRequest(command=$command, aggregateId=$aggregateId, type=$type, targetScope=$targetScope, " +
            "wait=$wait, timeout=$timeout, requestId=$requestId, expectedVersion=$expectedVersion)"

    private fun valueFields(): List<Any?> =
        listOf(command, aggregateId, type, targetScope, wait, timeout, requestId, expectedVersion)

    /**
     * Builds a [CommandRequest], one setter per field; [build] checks it as the constructor does. It is also the
     * receiver of the block of [toCommandRequest] and of the entries that take a command, with the same names.
     */
    @CommandRequestDsl
    class Builder<C : Any> internal constructor(private val command: C) {
        private var aggregateId: String? = null
        private var type: AggregateType? = null
        private var targetScope: TargetScope? = null
        private var wait: WaitPlan? = null
        private var timeout: Duration? = null
        private var requestId: String? = null
        private var expectedVersion: Int? = null

        fun aggregateId(aggregateId: String?): Builder<C> = apply { this.aggregateId = aggregateId }

        fun type(type: AggregateType?): Builder<C> = apply { this.type = type }

        /** The tenant and scope the request acts in, replacing both: a [TargetScope] that names no tenant names none. */
        fun targetScope(targetScope: TargetScope?): Builder<C> = apply { this.targetScope = targetScope }

        /**
         * The tenant the request acts in, in one call: [targetScope] naming [targetTenant] and keeping the scope it
         * named (the caller's own when it named none); null names no tenant.
         */
        fun targetTenant(targetTenant: String?): Builder<C> = apply {
            val scope = targetScope?.scope
            targetScope = if (targetTenant == null && scope == null) null else TargetScope.of(targetTenant, scope)
        }

        fun wait(wait: WaitPlan?): Builder<C> = apply { this.wait = wait }

        fun timeout(timeout: Duration?): Builder<C> = apply { this.timeout = timeout }

        /** [timeout] as a [java.time.Duration], for Java callers. */
        fun timeout(timeout: java.time.Duration?): Builder<C> = apply { this.timeout = timeout?.toKotlinDuration() }

        fun requestId(requestId: String?): Builder<C> = apply { this.requestId = requestId }

        fun expectedVersion(expectedVersion: Int?): Builder<C> = apply { this.expectedVersion = expectedVersion }

        /**
         * To the aggregate [ref] names: its type, its id when assigned, and its tenant as the target tenant
         * ([targetTenant]), keeping the scope (the caller's own unless [targetScope] named one), as [CommandRequest.to]
         * sends.
         */
        fun to(ref: AggregateRef): Builder<C> =
            type(ref.type).aggregateId(ref.id.takeIf { ref.isAssigned }).targetTenant(ref.tenantId)

        fun build(): CommandRequest<C> =
            CommandRequest(command, aggregateId, type, targetScope, wait, timeout, requestId, expectedVersion)
    }

    companion object {
        /** A request of [command] with the given fields; each left out is as [CommandRequest] describes. */
        @JvmStatic
        @JvmOverloads
        fun <C : Any> of(
            command: C,
            aggregateId: String? = null,
            type: AggregateType? = null,
            targetScope: TargetScope? = null,
            wait: WaitPlan? = null,
            timeout: Duration? = null,
            requestId: String? = null,
            expectedVersion: Int? = null,
        ): CommandRequest<C> =
            CommandRequest(command, aggregateId, type, targetScope, wait, timeout, requestId, expectedVersion)

        /** A [Builder] of a request of [command]. */
        @JvmStatic
        fun <C : Any> builder(command: C): Builder<C> = Builder(command)

        /**
         * A request of [command] to the aggregate [ref] names: its type and, when assigned, its id; its tenant the
         * target tenant, which is the caller's own unless it names another, its scope the caller's own. A target tenant that is the caller's own
         * is dropped, so `to(event.ref, command)` from a handler acting in the event's tenant is an ordinary request,
         * not a cross-scope one.
         */
        @JvmStatic
        fun <C : Any> to(ref: AggregateRef, command: C): CommandRequest<C> = builder(ref, command).build()

        /** A [Builder] of a request of [command] to the aggregate [ref] names, as [to] builds. */
        @JvmStatic
        fun <C : Any> builder(ref: AggregateRef, command: C): Builder<C> = Builder(command).to(ref)
    }
}
