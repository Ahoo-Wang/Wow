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

package me.ahoo.wow.runtime.access

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.snapshot.LruMap
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues

/**
 * How the built-in subscription [AccessStamping] stamps a history: at most [batch] streams in one stamp, so one long
 * history is written in statements of a bounded size, each by the version key; and the last stamped attributes of at
 * most [capacity] aggregates, which spare it a read of the previous stream. The host configures them (`wow.access.*`).
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal to Wow.
 */
class AccessStampOptions @InternalWowApi constructor(
    val batch: Int = DEFAULT_BATCH,
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(batch >= 1) { "A stamp writes at least one stream." }
        require(capacity >= 1) { "The stamping remembers at least one aggregate." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is AccessStampOptions && batch == other.batch && capacity == other.capacity

    override fun hashCode(): Int = 31 * batch + capacity

    override fun toString(): String = "AccessStampOptions(batch=$batch, capacity=$capacity)"

    companion object {
        /** The most streams one stamp writes by default. */
        const val DEFAULT_BATCH: Int = 1_000

        /** The aggregates whose last attributes the stamping remembers by default. */
        const val DEFAULT_CAPACITY: Int = 100_000
    }
}

/**
 * The built-in subscription [NAME] of one bounded context: it keeps the access attributes of each aggregate's history
 * its current ones. The kernel stamps each commit with the attributes after it ([AccessValues.ofStream]); when a
 * commit changed them (its attributes differ from those of the stream before it), this subscription stamps them over
 * every earlier stream of the aggregate ([AccessStamps.stamp]), as of the commit's version, in stamps of at most
 * [AccessStampOptions.batch] streams. So an events query authorizes an aggregate's whole history by what it is now,
 * within the subscription's lag.
 *
 * - **Every type**: the built-in owner and space are among the attributes, and any aggregate's scope can be
 *   transferred, so it follows every type of the context, from the beginning of the log.
 * - **The stream before**: the attributes this subscription last saw of the aggregate, when that was the stream right
 *   before ([AccessStampOptions.capacity] aggregates remembered), else the stream before as the log holds it. In either
 *   case it holds the attributes as of its version or later ones a stamp gave it, so a commit that changed nothing
 *   stamps nothing.
 * - **Idempotent**: a stamp writes only the streams not stamped as of a later version, so a replay, a retry or two
 *   owners of a partition at once leave each stream with its aggregate's latest attributes. A stream before that
 *   carries none (appended by a node of an earlier version, or imported) is stamped with the rest.
 * - **A stream without its attributes**: one not stamped, or stamped before the model declared the attributes it
 *   declares now, is not the follower's to stamp: it would replay the aggregate's state for each one. [unstamped] is
 *   told of its type, and the stamp of its aggregate's next commit writes it.
 * - **Run as [FunctionKind.PROJECT]**: it writes derived data of the log, as a projection does. A kind of its own would
 *   be a new value of an enum the wire carries (the OpenAPI document, the TypeScript client) for one built-in.
 */
@InternalWowApi
class AccessStamping(
    context: String,
    models: Collection<AggregateModel<*>>,
    private val kernel: Kernel,
    /** Where each type's history is stamped: its log; none, for a type whose log keeps no stamps. */
    private val stampsOf: (AggregateType) -> AccessStamps?,
    private val options: AccessStampOptions = AccessStampOptions(),
    /** Told of the type of each stream that carries no current attributes. */
    private val unstamped: (AggregateType) -> Unit = {},
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val last = LruMap<AggregateRef, Stamped>(options.capacity)

    init {
        require(this.models.keys.all { it.context == context }) { "Every stamped aggregate type is of $context." }
    }

    /** The subscription to register: of every aggregate type of the context, from the beginning of the log. */
    val subscription: Subscription = Subscription.builtIn(
        NAME,
        SubscriptionFilter(context, this.models.keys.mapTo(LinkedHashSet()) { it.name }),
        FunctionKind.PROJECT,
        ::stamp,
    )

    private suspend fun stamp(entry: SubscribedEntry) {
        val stream = entry.stream
        val ref = stream.ref
        val model = models[ref.type] ?: return
        val stamps = stampsOf(ref.type) ?: return
        val access = stream.access?.takeIf { AccessValues.declared(it).keys == model.accessNames }
        if (access == null) {
            last.remove(ref)
            unstamped(ref.type)
            return
        }
        val version = stream.version
        if (version > 1) {
            val before = last[ref]?.takeIf { it.version == version - 1 }?.access
                ?: kernel.streamAt(ref, version - 1)?.access
            if (before != access) {
                stampHistory(stamps, ref, access, version)
            }
        }
        last[ref] = Stamped(version, access)
    }

    /** Stamps [access] over the streams of [ref] before [asOf], at most a batch of them in each stamp. */
    private suspend fun stampHistory(stamps: AccessStamps, ref: AggregateRef, access: Map<String, Any?>, asOf: Int) {
        var from = 1
        var written = 0
        while (from < asOf) {
            val until = minOf(from + options.batch, asOf)
            written += stamps.stamp(ref, access, asOf, from, until)
            from = until
        }
        logger.debug { "Stamped the access attributes of $ref@$asOf over $written streams before it." }
    }

    /** The attributes of an aggregate's stream at [version], as this subscription last saw them. */
    private class Stamped(val version: Int, val access: Map<String, Any?>)

    override fun toString(): String = "AccessStamping($NAME)"

    @InternalWowApi
    companion object {
        /** The name of the subscription, `wow.access`. */
        const val NAME: String = "wow.access"

        private val logger = KotlinLogging.logger {}
    }
}
