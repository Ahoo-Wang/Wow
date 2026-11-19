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
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Violation
import me.ahoo.wow.spi.json.JsonReadFailures
import tools.jackson.core.JacksonException
import tools.jackson.core.exc.InputCoercionException
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/** How admission reads a request's body as its command type, step 4 of admission ([CommandGateway]), with [mapper]. */
internal class CommandBodies(private val mapper: JsonMapper) {
    private val failures = JsonReadFailures(mapper)

    /**
     * Each command type's reader of a body: Wow's JSON, refusing a property given more than once in one object, of
     * which a plain read would keep the last silently, and a property the command does not have, which Wow's JSON
     * ignores where an older or a newer writer's message must read (events, snapshots, cluster messages).
     */
    private val readers = ConcurrentHashMap<Class<*>, ObjectReader>()

    /**
     * Why [body] cannot be taken, or null when it can: it writes `\u0000` in a string or a key ([AdmissionLimits]); a
     * command object ([CommandBody.Value]) is checked as the JSON it is written as, which it must be.
     */
    fun bodyProblem(body: CommandBody): String? {
        val nul = when (body) {
            is CommandBody.Bytes -> AdmissionLimits.writesNul(body.bytes)
            is CommandBody.Tree -> AdmissionLimits.holdsNul(body.node)
            is CommandBody.Value -> try {
                AdmissionLimits.holdsNul(mapper.valueToTree(body.command))
            } catch (failure: JacksonException) {
                return "The command ${body.command.javaClass.name} is not written as JSON: ${failure.originalMessage}"
            }
        }
        return if (nul) "The body writes \\u0000 in a string, which no store keeps." else null
    }

    /**
     * [body] read as [type], or why it does not decode to one: the property, by its path, and the reason in Wow's
     * words, while Jackson's message, which can name the application's classes, goes to the log. A command object
     * ([CommandBody.Value]) is taken as it is when it is one.
     */
    fun decode(body: CommandBody, type: Class<*>): BodyRead = try {
        when (body) {
            is CommandBody.Bytes -> readerOf(type).readValue<Any>(body.bytes)
            is CommandBody.Tree -> readerOf(type).readValue<Any>(body.node)
            is CommandBody.Value -> body.command.takeIf { type.isInstance(it) }
        }?.let(::Decoded) ?: Undecodable(emptyList())
    } catch (failure: JacksonException) {
        logger.debug(failure) { "The body is not a ${type.name}: ${failure.originalMessage}" }
        Undecodable(listOfNotNull(violationOf(body, type, failure)))
    }

    private fun violationOf(body: CommandBody, type: Class<*>, failure: JacksonException): Violation? {
        // A command object is taken as it is, never read: only text and a tree fail to read.
        val json = if (body is CommandBody.Bytes) treeOf(body.bytes) else (body as CommandBody.Tree).node
        // Text that reads as JSON fails to read strictly only on a property it gives twice, at any depth, which a
        // reader may report through the property holding it: the duplicate is asked for first.
        val read = json?.let {
            if (body is CommandBody.Bytes && failure.readsStrictly()) failures.duplicateIn(body.bytes) else null
        } ?: json?.let { failures.of(it, type, failure) }
        return read?.let { Violation.of(it.path, it.reason.code, it.message) }
    }

    /** Whether [this] or a cause is the stream's own failure: a duplicate, not a number out of its type's range. */
    private fun JacksonException.readsStrictly(): Boolean = generateSequence<Throwable>(this) { it.cause }
        .any { it is StreamReadException && it !is InputCoercionException }

    private fun readerOf(type: Class<*>): ObjectReader =
        readers.computeIfAbsent(type) {
            failures.requestReader.forType(it).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }

    /** [bytes] as a tree, or null when they are not JSON. */
    private fun treeOf(bytes: ByteArray): JsonNode? = try {
        mapper.readTree(bytes)
    } catch (_: JacksonException) {
        null
    }
}

/** Named after the gateway, whose admission this is. */
private val logger = KotlinLogging.logger(CommandGateway::class.java.name)

/** A body read as its command type ([CommandBodies.decode]). */
internal sealed interface BodyRead

internal class Decoded(val value: Any) : BodyRead

/** A body that does not read as its command type; [violations] names the property and why, when it is JSON. */
internal class Undecodable(val violations: List<Violation>) : BodyRead

/** The body decoded, or what [undecodable] makes of one that is not. */
internal inline fun BodyRead.orElse(undecodable: (Undecodable) -> Nothing): Decoded = when (this) {
    is Decoded -> this
    is Undecodable -> undecodable(this)
}

/** The refusal of a body that does not read as the command [name]. */
internal fun Facts.undecodable(body: Undecodable, name: String): Admission.Refused =
    refuse(FrameworkCodes.COMMAND_MALFORMED, "The body is not a $name.", violations = body.violations)
