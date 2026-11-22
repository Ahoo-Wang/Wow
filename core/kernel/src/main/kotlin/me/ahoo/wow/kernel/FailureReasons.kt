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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.client.CommandRejectedException
import me.ahoo.wow.api.decide.RejectedException
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.code
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.name
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.path
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.phrase
import me.ahoo.wow.spi.subscription.FailureDescription.Companion.type
import me.ahoo.wow.spi.subscription.FailureDescription.PathSegment
import tools.jackson.core.JacksonException
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.exc.UnrecognizedPropertyException
import java.util.ServiceLoader

/**
 * The reason a parked aggregate records for a failure (`ParkedAggregate.reason`), which an operator reads and which
 * leaves the service through the ops routes, the park webhook and the ops context: what failed, never a value it
 * failed on (#6588). The full failure, its message and its stack trace, is in the node's log, which names the
 * aggregate, the subscription and the version it parked at.
 *
 * A reason is the failure's class name, then, after `: `, what is known of it, then each of at most [MAX_CAUSES] of
 * its causes, `caused by` its class name and what is known of it, separated by `; `:
 *
 * ```text
 * me.ahoo.wow.kernel.UnreadableEventException: event 'shop.order.Placed' revision 1 not readable;
 *   caused by tools.jackson.databind.exc.MismatchedInputException: cannot read 'quantity' as int
 * ```
 *
 * What is known of a failure is:
 *
 * - the message of one whose message is written for a caller ([isForCallers]: a [WowProblemException], a command's
 *   rejection, a decide function's [RejectedException], a [HandlingRejectedException]), which the caller already reads,
 *   and so nothing it should not see;
 * - else the description the first of [describers] gives ([FailureDescriber], built from the failure's structured
 *   fields);
 * - else nothing: the class name alone.
 *
 * The class name comes first, so the type an operator groups the entries by is the text before the first `: `, as the
 * ops context's `errorType` and the console read it. A reason is at most [MAX_LENGTH] characters.
 */
@InternalWowApi
class FailureReasons(private val describers: List<FailureDescriber>) {
    /** The reason [failure] parks an aggregate with. */
    fun reasonOf(failure: Throwable): String {
        val links = generateSequence(failure) { it.cause }.take(1 + MAX_CAUSES).distinct().toList()
        val causes = links.drop(1).map { cause -> "caused by ${linkOf(cause)}" }
        val told = listOfNotNull(known(failure)) + causes
        val name = failure.javaClass.name
        return (if (told.isEmpty()) name else "$name: ${told.joinToString("; ")}").take(MAX_LENGTH)
    }

    /** [failure]'s class name, then what is known of it. */
    private fun linkOf(failure: Throwable): String {
        val name = failure.javaClass.name
        return known(failure)?.let { "$name: $it" } ?: name
    }

    /** What is known of [failure]: its message when written for a caller, else its description; null for neither. */
    private fun known(failure: Throwable): String? =
        if (isForCallers(failure)) failure.message?.takeIf { it.isNotBlank() } else descriptionOf(failure)?.text

    @Suppress("TooGenericExceptionCaught") // A describer that fails describes nothing: the class name is the reason.
    private fun descriptionOf(failure: Throwable): FailureDescription? = describers.firstNotNullOfOrNull { describer ->
        try {
            describer.describe(failure)
        } catch (_: RuntimeException) {
            null
        }
    }

    @InternalWowApi
    companion object {
        /** The causes a reason names at most, after the failure itself. */
        const val MAX_CAUSES: Int = 3

        /** The longest reason, in characters. */
        const val MAX_LENGTH: Int = 1000

        /**
         * The describers of the modules on the classpath (`ServiceLoader`, from a module's
         * `META-INF/services/me.ahoo.wow.spi.subscription.FailureDescriber`), in the order of their class names, as
         * [FailureClassification.MODULES] are found.
         */
        val MODULES: List<FailureDescriber> by lazy { modulesOf(FailureReasons::class.java.classLoader) }

        /** The modules' describers [classLoader] finds, as [MODULES] are found. */
        fun modulesOf(classLoader: ClassLoader): List<FailureDescriber> =
            ServiceLoader.load(FailureDescriber::class.java, classLoader).sortedBy { it.javaClass.name }

        /** The modules' describers, then the built-in ones: no application's. */
        val DEFAULT: FailureReasons by lazy { of() }

        /**
         * The [application]'s describers, then the [modules]', then the caller's [own] (the runtime's), then the
         * built-in ones ([KernelDescriptions], [JacksonDescriptions]): the first that describes a failure decides.
         */
        fun of(
            application: List<FailureDescriber> = emptyList(),
            own: List<FailureDescriber> = emptyList(),
            modules: List<FailureDescriber> = MODULES,
        ): FailureReasons = FailureReasons(application + modules + own + KernelDescriptions + JacksonDescriptions)

        /**
         * Whether [failure]'s message is written for a caller, who reads it anyway: an answer of a service
         * ([WowProblemException]), a command's rejection ([CommandRejectedException]), a decide function's
         * ([RejectedException]), and a handling that ended in one ([HandlingRejectedException]).
         */
        fun isForCallers(failure: Throwable): Boolean =
            failure is WowProblemException || failure is CommandRejectedException ||
                failure is RejectedException || failure is HandlingRejectedException
    }
}

/** The words of the kernel's descriptions. */
internal enum class KernelPhrase {
    EVENT,
    REVISION,
    NOT_READABLE,
    TIMED_OUT_AFTER,
    MS,
}

/**
 * The kernel's own failures, described from their fields: an event the kernel cannot read ([UnreadableEventException],
 * by its name and revision; what it could not read is its cause's), and an attempt past its timeout
 * ([HandlerTimeoutException]).
 */
internal object KernelDescriptions : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? = when (failure) {
        is UnreadableEventException -> FailureDescription.of(
            phrase(KernelPhrase.EVENT),
            name(failure.name),
            phrase(KernelPhrase.REVISION),
            code(failure.revision.toLong()),
            phrase(KernelPhrase.NOT_READABLE),
        )

        is HandlerTimeoutException -> FailureDescription.of(
            phrase(KernelPhrase.TIMED_OUT_AFTER),
            code(failure.timeout.inWholeMilliseconds),
            phrase(KernelPhrase.MS),
        )

        else -> null
    }
}

/** The words of Jackson's descriptions. */
internal enum class JacksonPhrase {
    CANNOT_READ,
    AS,
    UNKNOWN_PROPERTY,
    MALFORMED_JSON_AT_LINE,
    COLUMN,
    UNREADABLE,
}

/**
 * Jackson's failures, which a codec reading a body, an event or a state throws: where in the document it failed (its
 * path: property names, array indices, a map's entry as `{key}`) and the type it read as, or the line and column of
 * text that does not parse; never the value, which the message quotes.
 */
internal object JacksonDescriptions : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? {
        if (failure !is JacksonException) return null
        val at = pathOf(failure)?.let(::path)
        val parts = when (failure) {
            is UnrecognizedPropertyException -> listOfNotNull(phrase(JacksonPhrase.UNKNOWN_PROPERTY), at)
            is MismatchedInputException -> listOfNotNull(phrase(JacksonPhrase.CANNOT_READ), at) +
                failure.targetType?.let { listOf(phrase(JacksonPhrase.AS), type(it)) }.orEmpty()

            // A location without a line (`-1`) is none.
            is StreamReadException -> failure.location?.takeIf { it.lineNr > 0 }?.let {
                listOf(
                    phrase(JacksonPhrase.MALFORMED_JSON_AT_LINE),
                    code(it.lineNr.toLong()),
                    phrase(JacksonPhrase.COLUMN),
                    code(it.columnNr.toLong()),
                )
            } ?: listOf(phrase(JacksonPhrase.UNREADABLE))

            else -> listOfNotNull(phrase(JacksonPhrase.UNREADABLE), at)
        }
        return FailureDescription.of(*parts.toTypedArray())
    }

    /** Where [failure] is in its document; null for its root. */
    private fun pathOf(failure: JacksonException): List<PathSegment>? = failure.path.map { reference ->
        val property = reference.propertyName
        when {
            property == null -> PathSegment.index(reference.index.coerceAtLeast(0))
            isData(reference.from()) -> PathSegment.KEY
            else -> runCatching { PathSegment.property(property) }.getOrDefault(PathSegment.KEY)
        }
    }.takeIf { it.isNotEmpty() }

    /** Whether the keys of [from], the object a reference is in, are data: a map's, or a JSON object's. */
    private fun isData(from: Any?): Boolean = when (from) {
        null, is Map<*, *>, is JsonNode -> true
        is Class<*> -> Map::class.java.isAssignableFrom(from) || JsonNode::class.java.isAssignableFrom(from)
        else -> false
    }
}
