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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore

/**
 * Turns the commands of one [input] type into the command that runs (decision D182): one command into one command,
 * at admission, after the input is decoded and authorized and before the result is validated. A transformer may
 * change the command's type, the aggregate it is sent to, its body and its headers; it cannot change the request id
 * (retries stay idempotent), the tenant or the scope, or the wait plan, which [CommandTarget] has no field for. A
 * command that must become several is a saga's work.
 *
 * What it serves:
 *
 * - a command whose request names no aggregate, which the transformer finds, such as changing a password by user name
 *   ([ReservationTargetResolver]);
 * - a mounted command (`@MountedCommand`), which no aggregate decides, such as an import that becomes a creation
 *   command or an external callback in a provider's shape.
 *
 * At most one transformer takes an input type, and every mounted command needs exactly one: the gateway refuses to be
 * built otherwise. The gateway authorizes the result again when the type or the aggregate changed, and refuses it as
 * `wow.aggregate.not-found` when it names no aggregate and does not create one. The command's result
 * ([me.ahoo.wow.api.result.CommandResult.transformedFrom]) and its event stream's headers ([TRANSFORMED_FROM],
 * [TRANSFORMED_FROM_ID]) record the input.
 */
interface CommandTransformer {
    /** The command type this transformer takes. */
    val input: CommandType

    /** The command [input] becomes. */
    suspend fun transform(input: CommandInput): CommandTarget

    companion object {
        /** The event stream header that names the full type name of the command a request named. */
        const val TRANSFORMED_FROM: String = "wow.transformed-from"

        /** The event stream header that names the aggregate id the request gave, when it gave one. */
        const val TRANSFORMED_FROM_ID: String = "wow.transformed-from-id"
    }
}

/**
 * A request as a transformer sees it: decoded, before the command is built. Its identity may be incomplete:
 * [aggregateId] is null when the request named none. The [requestId], the [caller] and the [tenantId] the command runs
 * in (the caller's, or the one the request targets) are only read: the command that runs keeps them.
 */
data class CommandInput(
    val type: CommandType,
    val body: Any,
    val aggregateId: String?,
    val headers: Map<String, String>,
    val requestId: String,
    val caller: Caller,
    val tenantId: String = caller.tenantId,
)

/**
 * The command a transformer sends, and to which aggregate: a [body] of [type], decided by an aggregate the gateway
 * serves, to the aggregate [aggregateId] of that type, with [headers]. The gateway builds the [me.ahoo.wow.api.decide.Command]
 * from it by its usual rules: the tenant, the scope, the request id and the wait plan are the request's. A null
 * [aggregateId] is allowed only for a command that creates its aggregate, whose id the gateway then generates.
 */
data class CommandTarget(
    val type: CommandType,
    val aggregateId: String?,
    val body: Any,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(type.type.isInstance(body)) { "The body of ${type.name} is a ${body.javaClass.name}." }
        require(aggregateId == null || aggregateId.isNotBlank()) { "An aggregate id, when given, is not blank." }
        val reserved = headers.keys.firstOrNull(::isReservedHeader)
        require(reserved == null) { "Header $reserved is reserved: a transformer may not add it." }
    }
}

/**
 * The built-in transformer that finds a command's aggregate by a reserved key (decision D50): the type, the body and
 * the headers stay as they are, and a request that names no aggregate is sent to the one of [aggregate] holding the
 * [key] of its input in [namespace] of [reservations], such as changing a password by user name. A request that names
 * an aggregate keeps it; one whose key no aggregate of [aggregate] holds names none, which the gateway refuses as
 * `wow.aggregate.not-found`.
 *
 * The key is looked up in the command's tenant (finding A1-3), or across tenants when it was reserved with
 * [ReservationTenancy.SYSTEM]; either way only an owner of [aggregate] in the command's tenant is found, its id
 * read from the owner's fields (finding A1-10), so a key held in another tenant finds no aggregate.
 */
internal class ReservationTargetResolver(
    override val input: CommandType,
    private val aggregate: AggregateType,
    private val namespace: String,
    private val reservations: ReservationStore,
    private val tenancy: ReservationTenancy = ReservationTenancy.TENANT,
    private val key: (CommandInput) -> String?,
) : CommandTransformer {
    init {
        require(namespace.isNotBlank()) { "A reservation namespace is not blank." }
    }

    override suspend fun transform(input: CommandInput): CommandTarget {
        val id = input.aggregateId ?: key(input)?.let { key ->
            val tenantId = if (tenancy == ReservationTenancy.SYSTEM) ReservationScope.SYSTEM_TENANT else input.tenantId
            reservations.find(ReservationScope(aggregate.context, tenantId, namespace), key)?.owner
                ?.takeIf { it.type == aggregate && it.tenantId == input.tenantId }?.id
        }
        return CommandTarget(input.type, id, input.body, input.headers)
    }
}

private val RESERVED_HEADER_PREFIXES = listOf("wow-", "wow.")

/** Whether [header] is in the framework's namespace (`Wow-…`, `wow.…`), which only the framework writes. */
internal fun isReservedHeader(header: String): Boolean =
    RESERVED_HEADER_PREFIXES.any { header.startsWith(it, ignoreCase = true) }
