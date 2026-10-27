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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
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
 * - [id]: the aggregate's id; null when the command names no aggregate id: a creation, whose id the server assigns,
 *   or an `@Unaddressed` command, whose transformer finds its aggregate. The command's model on the server decides
 *   which, never the client.
 * - [type]: the aggregate type; null to derive it from the command's class, from the models of the service's own
 *   contexts and the contracts of its peers. A class no aggregate known to the client decides, or several decide, fails
 *   when it is sent, as does a lifecycle command (`DeleteAggregate`, ...), which every aggregate takes:
 *   `CommandRequest.of(DeleteAggregate, id, type = ShopContract.Cart.TYPE)`.
 * - [targetTenant] and [targetScope]: the tenant and scope a cross-scope caller acts in (`Wow-Scope`); the caller's
 *   own when unset. They are the only source of the tenant and scope: a target tenant that is the caller's own changes
 *   nothing.
 * - [wait] (`Wow-Wait`; the server's default plan when null) within [timeout] when given (`Wow-Wait-Timeout`).
 * - [requestId], which makes the command idempotent (`Wow-Request-Id`; a new one when null, kept by every retry).
 * - [expectedVersion] of the aggregate (`If-Match`; 0 for one never written).
 *
 * Build it with [of], [builder] or [to]; the constructor is internal to Wow. Fields may be added.
 */
class CommandRequest @InternalWowApi constructor(
    val command: Any,
    val id: String? = null,
    val type: AggregateType? = null,
    val targetTenant: String? = null,
    val targetScope: Scope? = null,
    val wait: WaitPlan? = null,
    val timeout: Duration? = null,
    val requestId: String? = null,
    val expectedVersion: Int? = null,
) {
    init {
        require(command !is CommandRequest) { "A CommandRequest sends a command object, not another request." }
        require(command !is Command<*>) {
            "A CommandRequest sends the command object, not a Command: its id and scope are the request's."
        }
        require(command.javaClass !in Schedules.COMMANDS.values) {
            "${command.javaClass.simpleName} is a schedule command the kernel decides: a decide function schedules " +
                "with DecideScope.schedule."
        }
        require(id == null || id.isNotBlank()) { "An aggregate id, when given, is not blank." }
        require(targetTenant == null || targetTenant.isNotBlank()) { "A target tenant, when given, is not blank." }
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
    fun toBuilder(): Builder = Builder(command).id(id).type(type).targetTenant(targetTenant).targetScope(targetScope)
        .wait(wait).timeout(timeout).requestId(requestId).expectedVersion(expectedVersion)

    override fun equals(other: Any?): Boolean =
        this === other || other is CommandRequest && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "CommandRequest(command=$command, id=$id, type=$type, targetTenant=$targetTenant, targetScope=$targetScope, " +
            "wait=$wait, timeout=$timeout, requestId=$requestId, expectedVersion=$expectedVersion)"

    private fun valueFields(): List<Any?> =
        listOf(command, id, type, targetTenant, targetScope, wait, timeout, requestId, expectedVersion)

    /** Builds a [CommandRequest], one setter per field; [build] checks it as the constructor does. */
    class Builder internal constructor(private val command: Any) {
        private var id: String? = null
        private var type: AggregateType? = null
        private var targetTenant: String? = null
        private var targetScope: Scope? = null
        private var wait: WaitPlan? = null
        private var timeout: Duration? = null
        private var requestId: String? = null
        private var expectedVersion: Int? = null

        fun id(id: String?): Builder = apply { this.id = id }

        fun type(type: AggregateType?): Builder = apply { this.type = type }

        fun targetTenant(targetTenant: String?): Builder = apply { this.targetTenant = targetTenant }

        fun targetScope(targetScope: Scope?): Builder = apply { this.targetScope = targetScope }

        fun wait(wait: WaitPlan?): Builder = apply { this.wait = wait }

        fun timeout(timeout: Duration?): Builder = apply { this.timeout = timeout }

        /** [timeout] as a [java.time.Duration], for Java callers. */
        fun timeout(timeout: java.time.Duration?): Builder = apply { this.timeout = timeout?.toKotlinDuration() }

        fun requestId(requestId: String?): Builder = apply { this.requestId = requestId }

        fun expectedVersion(expectedVersion: Int?): Builder = apply { this.expectedVersion = expectedVersion }

        fun build(): CommandRequest =
            CommandRequest(command, id, type, targetTenant, targetScope, wait, timeout, requestId, expectedVersion)
    }

    companion object {
        /** A request of [command] with the given fields; each left out is as [CommandRequest] describes. */
        @JvmStatic
        @JvmOverloads
        fun of(
            command: Any,
            id: String? = null,
            type: AggregateType? = null,
            targetTenant: String? = null,
            targetScope: Scope? = null,
            wait: WaitPlan? = null,
            timeout: Duration? = null,
            requestId: String? = null,
            expectedVersion: Int? = null,
        ): CommandRequest =
            CommandRequest(command, id, type, targetTenant, targetScope, wait, timeout, requestId, expectedVersion)

        /** A [Builder] of a request of [command]. */
        @JvmStatic
        fun builder(command: Any): Builder = Builder(command)

        /**
         * A request of [command] to the aggregate [ref] names: its type and, when assigned, its id; its tenant the
         * [targetTenant], which is the caller's own unless it names another.
         */
        @JvmStatic
        fun to(ref: AggregateRef, command: Any): CommandRequest = builder(ref, command).build()

        /** A [Builder] of a request of [command] to the aggregate [ref] names, as [to] builds. */
        @JvmStatic
        fun builder(ref: AggregateRef, command: Any): Builder = Builder(command).type(ref.type)
            .id(ref.id.takeIf { ref.isAssigned }).targetTenant(ref.tenantId)
    }
}
