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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.TransformedFrom
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.kernel.DecideFunction
import me.ahoo.wow.kernel.RejectionCodes
import me.ahoo.wow.runtime.admission.CommandGateway.Companion.UNADDRESSED
import me.ahoo.wow.spi.tenant.TenantConflictException
import me.ahoo.wow.spi.tenant.TenantPolicy
import java.util.concurrent.ConcurrentHashMap

/**
 * Step 5 of admission ([CommandGateway]): the command a [CommandTransformer] makes of the one a request names, decided,
 * authorized and addressed again for the aggregate it goes to.
 *
 * At most one of [transformers] takes a command type, each takes one this node decides or mounts (or one of a context
 * it does not serve), and every mounted command needs one: the gateway is not built otherwise ([transformersOf]).
 */
internal class Transformations(
    registry: ModelRegistry,
    transformers: List<CommandTransformer>,
    private val tenants: TenantPolicy,
    private val creations: CreationIds,
    private val authorizer: CommandAuthorizer,
) {
    /** The transformers, by the full type name of the command they take. */
    val transformers: Map<String, CommandTransformer> = transformersOf(registry, transformers)

    /** The input types a transformer rejected with events, warned about once each ([transformerRejected]). */
    private val rejectedWithEvents: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The models that decide each command class, with the command's type. */
    private val deciders: Map<Class<*>, List<Pair<AggregateModel<*>, CommandType>>> = registry.models.flatMap { model ->
        model.commandTypes.map { it.type to (model to it) }
    }.groupBy({ it.first }, { it.second })

    /**
     * [commandType], its body [decoded] in [tenantId] with [headers], transformed by [transformer]: the command it
     * becomes, for the aggregate it names in the tenant [tenants] gives that aggregate's type for [target], in [scope];
     * or the refusal of [facts]' request.
     */
    suspend fun transform(
        transformer: CommandTransformer,
        request: ServerCommandRequest,
        facts: Facts,
        commandType: CommandType,
        decoded: Decoded,
        headers: Map<String, String>,
        tenantId: String,
        target: TargetScope?,
        scope: Scope,
    ): Transformed {
        val input = CommandInput(
            commandType,
            decoded.value,
            request.aggregateId,
            headers,
            facts.requestId,
            request.caller,
            tenantId,
        )
        val result = try {
            transformer.transform(input)
        } catch (rejected: RejectedException) {
            return transformerRejected(facts, commandType, rejected)
        }
        facts.transformedFrom = TransformedFrom(commandType.name, request.aggregateId)
        val (targetModel, targetDecider) = decidedBy(result.type)
            ?: return facts.refuse(
                FrameworkCodes.COMMAND_UNHANDLED,
                "${commandType.name} became ${result.type.name}, which no aggregate served here decides.",
            )
        val targetType = targetModel.type
        facts.function = functionOf(targetType, result.type)
        val targetTenant = try {
            tenants.tenantOf(targetType, request.caller, target)
        } catch (conflict: TenantConflictException) {
            return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, conflict.message!!)
        }
        val ownTarget = targetModel.idSource == AggregateIdSource.OWNER
        val owner = scope.ownerId
        val aggregateId = result.aggregateId ?: if (ownTarget && owner != null) {
            owner
        } else if (!ownTarget && targetDecider.creation != Creation.NONE) {
            creations.idOf(targetType, targetTenant, facts.requestId, scope, targetModel.ownership)
        } else {
            facts.ref = targetType.ref(targetTenant, UNADDRESSED)
            return facts.refuse(
                FrameworkCodes.AGGREGATE_NOT_FOUND,
                "No $targetType is found for ${commandType.name} to send ${result.type.name} to.",
            )
        }
        val ref = targetType.ref(targetTenant, aggregateId)
        AdmissionLimits.oversized(facts.requestId, aggregateId, targetTenant, scope)?.let {
            facts.ref = ref
            return facts.refuse(FrameworkCodes.COMMAND_MALFORMED, it)
        }
        if (result.type != commandType || ref != facts.ref) {
            facts.ref = ref
            val action = CommandAction(result.type, ref, scope, targetDecider.creation)
            authorizer.authorize(request, action)?.let { return facts.refuse(it) }
        }
        return Resolved(
            targetModel,
            result.type,
            result.body,
            result.headers + provenance(commandType, request.aggregateId),
        )
    }

    /** The model that decides commands of [type], with its decide function; null when none does. */
    private fun decidedBy(type: CommandType): Pair<AggregateModel<*>, DecideFunction<*>>? {
        val (model, _) = deciders[type.type].orEmpty().singleOrNull { (_, commandType) -> commandType.name == type.name }
            ?: return null
        return model.decider(type.type)?.let { model to it }
    }

    /**
     * The refusal of a command whose transformer rejected it, answered as the kernel answers a decide function's
     * rejection: the code qualified with the input's aggregate type ([RejectionCodes.qualified]), with its message and
     * extensions. Admission appends nothing, so the events a rejection carries are dropped, warned about once per
     * [input] type.
     */
    private fun transformerRejected(facts: Facts, input: CommandType, rejected: RejectedException): Admission.Refused {
        if (rejected.events.isNotEmpty() && rejectedWithEvents.add(input.name)) {
            logger.warn {
                "The transformer of ${input.name} rejected it with events: admission appends nothing, so they are " +
                    "dropped. Reject it without events."
            }
        }
        val rejection = RejectionCodes.qualified(rejected.rejection, facts.type, input.name)
        return facts.refuse(rejection.code, rejection.message, extensions = rejection.extensions)
    }

    private companion object {
        /** The headers that name the input of a transformed command. */
        private fun provenance(input: CommandType, aggregateId: String?): Map<String, String> = buildMap {
            put(CommandTransformer.TRANSFORMED_FROM, input.name)
            aggregateId?.let { put(CommandTransformer.TRANSFORMED_FROM_ID, it) }
        }

        /**
         * [transformers] by the full type name of the command each takes; fails when two take one type, when one takes
         * a command no model of [registry] decides or mounts under that name and class (unless the name is of a context
         * [registry] does not serve), or when a mounted command has none.
         */
        private fun transformersOf(
            registry: ModelRegistry,
            transformers: List<CommandTransformer>,
        ): Map<String, CommandTransformer> {
            val ambiguous = transformers.groupBy { it.input.name }.filterValues { it.size > 1 }
            require(ambiguous.isEmpty()) {
                "More than one transformer takes ${ambiguous.keys.sorted().joinToString()}: at most one takes a command " +
                    "type: " +
                    ambiguous.values.flatten().joinToString { it.javaClass.name }
            }
            val known = registry.models.flatMap { it.commandTypes + it.mountedCommandTypes }.associateBy { it.name }
            transformers.forEach { transformer ->
                val input = transformer.input
                val type = known[input.name]
                require(type != null || input.name.substringBefore('.') !in registry.contexts) {
                    "${transformer.javaClass.name} takes ${input.name}, which no aggregate served here decides or mounts."
                }
                require(type == null || type.type == input.type) {
                    "${transformer.javaClass.name} takes ${input.name} as ${input.type.name}, but it is a ${type!!.type.name}."
                }
                require(registry.models.none { it.idFromBody(input.type) }) {
                    "${transformer.javaClass.name} takes ${input.name}, whose body names its aggregate (@AggregateId): " +
                        "no transformer finds the aggregate of such a command."
                }
            }
            val byName = transformers.associateBy { it.input.name }
            val unclaimed = registry.models.flatMap { it.mountedCommandTypes }.filterNot { it.name in byName }
            require(unclaimed.isEmpty()) {
                "No transformer takes the mounted commands ${unclaimed.joinToString { it.name }}: a mounted command " +
                    "needs exactly one."
            }
            return byName
        }
    }
}

/** What [Transformations.transform] ends in: the command a transformer made, or the request's refusal. */
internal sealed interface Transformed

private val logger = KotlinLogging.logger(CommandGateway::class.java.name)
