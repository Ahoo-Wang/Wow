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

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandGateway.Companion.UNADDRESSED
import me.ahoo.wow.runtime.wait.CommandRelay
import me.ahoo.wow.runtime.wait.admissionRejected
import me.ahoo.wow.spi.observation.ObservedOutcome
import me.ahoo.wow.spi.tenant.TenantPolicy
import java.time.Clock

// What admission ([CommandGateway]) works with and ends in: the facts it decided about a request, the command it
// builds, and how it ends.

/** The command admission builds: a [body] of [type], decided by [model], with [headers]. */
internal class Resolved(
    val model: AggregateModel<*>,
    val type: CommandType,
    val body: Any,
    val headers: Map<String, String>,
) : Transformed

/** The facts decided so far about a request, to refuse it with. */
internal class Facts(
    private val request: ServerCommandRequest,
    val commandId: String,
    tenants: TenantPolicy,
    private val clock: Clock,
) {
    val type = AggregateType(request.context, request.aggregate)
    val requestId: String = request.requestId?.takeIf { it.isNotBlank() } ?: commandId
    var ref: AggregateRef = type.ref(
        tenants.fixedTenant(type) ?: request.caller.tenantId,
        request.aggregateId?.takeIf { it.isNotBlank() } ?: UNADDRESSED,
    )
    private var resolved: FunctionInfo? = null

    /** The command's function: as the request names it until admission resolves it, built only when needed. */
    var function: FunctionInfo
        get() = resolved ?: FunctionInfo(
            FunctionKind.COMMAND,
            type.context,
            type.name,
            request.command.substringAfterLast('.').ifBlank { request.command },
        ).also { resolved = it }
        set(value) {
            resolved = value
        }
    var transformedFrom: TransformedFrom? = null

    fun refuse(denied: Authorization.Denied): Admission.Refused = refuse(denied.code, denied.message)

    fun refuse(
        code: String,
        message: String,
        status: CommandResult.Status = CommandResult.Status.REJECTED,
        violations: List<Violation> = emptyList(),
        extensions: Map<String, Any?> = emptyMap(),
    ): Admission.Refused {
        val rejected = admissionRejected(
            commandId = commandId,
            requestId = requestId,
            correlationId = commandId,
            ref = ref,
            function = function,
            error = ErrorInfo(code, message, violations, extensions),
            now = clock.millis(),
        )
        return Admission.Refused(rejected.copyWith(status = status, transformedFrom = transformedFrom))
    }
}

internal sealed interface Admission {
    class Admitted(
        val model: AggregateModel<*>,
        val command: Command<*>,
        val transformedFrom: TransformedFrom?,
        /** The synchronous call the command came with, from a service identity. */
        val call: CallContext? = null,
    ) : Admission

    class Refused(val result: CommandResult) : Admission, Transformed

    /** A peer context's command, sent to its command topic through [relay]. */
    data class Relayed(
        val command: Command<*>,
        val function: FunctionInfo,
        val relay: CommandRelay,
    ) : Admission

    /** How the admission ended, as its observation names it. */
    val observedName: ObservedOutcome
        get() = if (this is Refused) ObservedOutcome.REFUSED else ObservedOutcome.ADMITTED
}

internal val CommandType.shortName: String get() = name.substringAfterLast('.')

internal fun functionOf(type: AggregateType, command: CommandType): FunctionInfo =
    FunctionInfo(FunctionKind.COMMAND, type.context, type.name, command.shortName)
