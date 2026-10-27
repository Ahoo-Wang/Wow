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

package me.ahoo.wow.analytics.catalog.server

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetReference
import me.ahoo.wow.analytics.catalog.api.dataset.DatasetStatus
import me.ahoo.wow.analytics.catalog.domain.dataset.DatasetState
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.client.SendOptions
import me.ahoo.wow.api.client.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.client.NamedCommand
import me.ahoo.wow.http.problem.Problem
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import tools.jackson.databind.JsonNode

/** A command a dataset's row offers on the aggregate a field refers to: its name, what it says it does, its inputs. */
data class ReferenceCommand(val name: String, val description: String?, val inputs: List<CommandInput>)

/**
 * One input of a [ReferenceCommand]: a property of the command's body, its JSON [type] (`string`, `number`,
 * `integer` or `boolean`), whether it is [required] and may be [nullable], and the [options] of an enumeration.
 */
data class CommandInput(
    val name: String,
    val type: String,
    val required: Boolean,
    val nullable: Boolean = false,
    val options: List<String>? = null,
    val description: String? = null,
)

/** A REFERENCE field of a dataset, the aggregate it refers to, and the commands its rows offer on it. */
data class ReferenceField(
    val field: String,
    val context: String,
    val aggregate: String,
    val commands: List<ReferenceCommand>,
)

/** The reference actions of a served dataset, by field. */
data class ReferenceActions(val dataset: String, val fields: List<ReferenceField>)

/** Why the relay refuses a request: the [problem] it is answered with. */
class ReferenceActionRefusal(val problem: Problem) : RuntimeException(problem.detail)

/**
 * The actions of a dataset's rows on the aggregates they refer to: a console offers, on a
 * row, the commands of the aggregate a REFERENCE field holds the id of, and sends them here; the catalog sends each to
 * the owning service as the caller, so that service authorizes it as it would a direct call. The browser talks to
 * the catalog alone: no other service's URL, no CORS.
 *
 * - [actionsOf]: per REFERENCE field of the version a dataset serves, the commands the owning context's Manifest
 *   declares on the aggregate (read through the [manifests] of the peers [described] by their Manifest,
 *   `wow.peers.<context>.contract: manifest`) that create nothing and take a flat body, whose properties
 *   are strings, numbers, booleans or enumerations: what a row's form can ask for. A context without such a client
 *   offers nothing, nor does one whose Manifest cannot be read now.
 * - [send]: a command [actionsOf] offers, sent through the service's [commands] as a [NamedCommand] to the aggregate
 *   of the row's id, in the caller's tenant, waiting with the owning service's default plan (until its snapshot holds
 *   the commit, or it is committed). An unknown dataset or field is 404, a command not offered 400
 *   `wow.command.unhandled`: the relay is no open door. The owning service's refusal passes through, in its code
 *   and words.
 */
class DatasetReferenceActions(
    private val datasets: suspend (String) -> DatasetState?,
    private val manifests: suspend (context: String, caller: Caller) -> Manifest,
    private val described: Set<String>,
    private val commands: CommandClient,
) {
    /** The reference actions of [dataset] for [caller], or null when no dataset of that id is served. */
    suspend fun actionsOf(dataset: String, caller: Caller): ReferenceActions? {
        val fields = referencesOf(dataset) ?: return null
        val read = fields.values.map { it.context }.distinct().associateWith { manifestOf(it, caller) }
        return ReferenceActions(
            dataset,
            fields.map { (field, reference) ->
                ReferenceField(
                    field,
                    reference.context,
                    reference.aggregate,
                    read[reference.context]?.let { offered(it, reference) }.orEmpty().map { (command, inputs) ->
                        ReferenceCommand(command.name, command.description, inputs)
                    },
                )
            },
        )
    }

    /** Sends [command] with [body] to the aggregate [id] of [field] of [dataset] refers to, as [caller]. */
    @Suppress(
        "LongParameterList", // The relay's path, its body and its caller.
        "TooGenericExceptionCaught", // The owning service's answers pass through; any other failure as it is.
    )
    suspend fun send(
        dataset: String,
        field: String,
        id: String,
        command: String,
        body: JsonNode,
        caller: Caller,
    ): CommandResult {
        val (reference, manifest) = offeredOf(dataset, field, command, caller)
        if (!body.isObject) {
            throw ReferenceActionRefusal(
                problem(BAD_REQUEST, FrameworkErrors.COMMAND_MALFORMED, "A command's body is a JSON object."),
            )
        }
        val type = AggregateType(reference.context, reference.aggregate)
        return try {
            commands.of(caller).wait(
                NamedCommand("$type.$command", body),
                type.ref(caller.tenantId, id),
                SendOptions.of(wait = WaitPlan.DEFAULT),
            )
        } catch (failure: RuntimeException) {
            throw refusalOf(failure, manifest)
        }
    }

    /** The owning service's answer [failure] passed through, in its code and words; any other failure as it is. */
    private fun refusalOf(failure: RuntimeException, manifest: Manifest): RuntimeException = when (failure) {
        is CommandRejectedException -> ReferenceActionRefusal(
            Problem.of(
                statusOf(manifest, failure.code),
                "Rejected",
                failure.result.error?.message,
                failure.code,
                result = failure.result,
            ),
        )

        is WowProblemException -> ReferenceActionRefusal(
            Problem.of(failure.status, "Not done", failure.message, failure.code, result = failure.result),
        )

        else -> failure
    }

    /**
     * The aggregate [field] of [dataset] refers to and its context's Manifest, once that Manifest declares [command]
     * among those a row offers; else the refusal: 404 for an unknown dataset or field, 400 for a command not offered.
     */
    private suspend fun offeredOf(
        dataset: String,
        field: String,
        command: String,
        caller: Caller,
    ): Pair<DatasetReference, Manifest> {
        val fields = referencesOf(dataset)
        val reference = fields?.get(field)
            ?: throw notFound(if (fields == null) "No dataset $dataset is served." else "$field is no reference field of $dataset.")
        val manifest = manifestOf(reference.context, caller)
            ?.takeIf { manifest -> offered(manifest, reference).any { it.first.name == command } }
            ?: throw unhandled(reference, command)
        return reference to manifest
    }

    /** The REFERENCE fields of the version [dataset] serves, by name; null when it serves none. */
    private suspend fun referencesOf(dataset: String): Map<String, DatasetReference>? {
        val state = datasets(dataset)?.takeIf { it.exists && it.status != DatasetStatus.RETIRED } ?: return null
        val published = state.published() ?: return null
        return published.artifact.fields.mapNotNull { field -> field.reference?.let { field.name to it } }.toMap()
    }

    @Suppress("TooGenericExceptionCaught") // A peer that cannot answer now offers nothing; the listing still answers.
    private suspend fun manifestOf(context: String, caller: Caller): Manifest? {
        if (context !in described) return null
        return try {
            manifests(context, caller)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(failure) { "The Manifest of $context could not be read: its rows offer no actions now." }
            null
        }
    }

    private fun unhandled(reference: DatasetReference, command: String) = ReferenceActionRefusal(
        problem(
            BAD_REQUEST,
            FrameworkErrors.COMMAND_UNHANDLED,
            "$command is no action the rows offer on ${reference.context}.${reference.aggregate}.",
        ),
    )

    private fun notFound(detail: String) = ReferenceActionRefusal(Problem.of(NOT_FOUND, "Not Found", detail, null))

    private fun problem(status: Int, code: String, detail: String) = Problem.of(status, "Bad Request", detail, code)

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private val SCALARS = setOf("string", "number", "integer", "boolean")

        /**
         * The commands of [reference]'s aggregate in [manifest] a row offers, with their inputs: those that create
         * nothing, are no mounted command and take a flat body.
         */
        fun offered(manifest: Manifest, reference: DatasetReference): List<Pair<CommandManifest, List<CommandInput>>> {
            val aggregate = manifest.contexts.firstOrNull { it.name == reference.context }
                ?.aggregates?.firstOrNull { it.name == reference.aggregate } ?: return emptyList()
            return aggregate.commands
                .filter { it.creation == Creation.NONE && !it.mounted }
                .mapNotNull { command -> inputsOf(manifest, command.schema)?.let { command to it } }
        }

        /** The inputs of the body [schema] names, or null when it is not flat. */
        fun inputsOf(manifest: Manifest, schema: String): List<CommandInput>? {
            val definition = manifest.defs[schema.removePrefix(DEFS)] as? Map<*, *> ?: return null
            if (definition["type"] != "object") return null
            val required = (definition["required"] as? List<*>).orEmpty().toSet()
            val properties = definition["properties"] as? Map<*, *> ?: return emptyList()
            return properties.map { (name, property) ->
                val spec = property as? Map<*, *> ?: return null
                val types = when (val type = spec["type"]) {
                    is String -> listOf(type)
                    is List<*> -> type.filterIsInstance<String>()
                    else -> return null
                }
                val scalar = types.filter { it != "null" }.singleOrNull()?.takeIf { it in SCALARS } ?: return null
                val options = (spec["enum"] as? List<*>)?.map { it.toString() }
                CommandInput(
                    name = name.toString(),
                    type = scalar,
                    required = name in required,
                    nullable = "null" in types,
                    options = options,
                    description = spec["description"] as? String,
                )
            }
        }

        /** The HTTP status [code] is answered with: as [manifest] or the framework declares it, else a rejection's. */
        fun statusOf(manifest: Manifest, code: String): Int =
            (manifest.errors + manifest.contexts.flatMap { it.errors } + FrameworkErrors.ALL)
                .firstOrNull { it.code == code }?.status ?: FrameworkErrors.DOMAIN_REJECTION_STATUS

        private const val DEFS = "#/\$defs/"
    }
}
