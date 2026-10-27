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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.DecideScope
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.schedule.ScheduleCancelled
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.api.schedule.ScheduleSet
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationKey
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.wait.WaitContext
import java.util.UUID
import kotlin.time.Duration

/** One attempt's registrations. Allocates its lists only when a decide function registers something. */
@Suppress("LongParameterList")
internal class AttemptScope(
    override val command: Command<*>,
    override val ids: Ids,
    override val loaded: CachedState,
    private val reservations: ReservationStore?,
    private val reservationTtl: Duration,
    private val wait: WaitContext?,
    private val observer: Observer,
    /** The type name and JSON of a command scheduled for a target. */
    private val encode: (AggregateRef, Any) -> Pair<String, String>,
) : DecideScope, KernelLifecycle.MetadataScope {
    private var compensations: MutableList<suspend () -> Unit>? = null
    private var afterAppendActions: MutableList<suspend () -> Unit>? = null
    private var results: MutableMap<String, Any>? = null
    private var reserved: MutableList<ReservationKey>? = null
    private var released: MutableList<ReservationKey>? = null
    private var hold: String? = null

    /** The version the attempt's stream committed at, once [afterAppend] runs its actions. */
    private var committed = ReservationStore.UNVERSIONED
    private var scheduled: LinkedHashMap<String, Any>? = null

    override fun schedule(key: String, rule: ScheduleRule, command: Any, target: AggregateRef) {
        if (key.isBlank()) throw ScopeMisuse.Argument("A schedule needs a key.")
        val (type, body) = encode(target, command)
        changes().apply {
            remove(key)
            put(key, ScheduleSet(key, rule, target, type, body))
        }
    }

    override fun cancelSchedule(key: String) {
        if (key.isBlank()) throw ScopeMisuse.Argument("A schedule needs a key.")
        changes().apply {
            // A key set earlier in this decision and not kept before: the set is undone, nothing to cancel.
            val undone = remove(key) is ScheduleSet && key !in loaded.schedules
            if (!undone) {
                put(key, ScheduleCancelled(key))
            }
        }
    }

    private fun changes() = scheduled ?: LinkedHashMap<String, Any>().also { scheduled = it }

    /** The schedule events of this decision, in the order their keys were last changed. */
    fun scheduleEvents(): List<Any> = scheduled?.values?.toList().orEmpty()

    override fun putResult(key: String, value: Any) {
        (results ?: LinkedHashMap<String, Any>().also { results = it })[key] = value
    }

    fun results(): Map<String, Any> = results ?: emptyMap()

    /**
     * What the append carries for delivery: the wait context when the caller waits beyond COMMITTED, the
     * keys this attempt reserved pending and released, and the append's [trace]; null when
     * there is none of them.
     */
    fun transport(
        trace: Map<String, String>
    ): Transport? = if (wait == null && reserved == null && released == null) {
        trace.takeIf { it.isNotEmpty() }?.let { Transport(trace = it) }
    } else {
        Transport(
            wait = wait,
            reservations = reserved?.toList().orEmpty(),
            trace = trace,
            releases = released?.toList().orEmpty(),
        )
    }

    override suspend fun reserve(namespace: String, key: String): Boolean =
        reserve(namespace, key, ReservationTenancy.TENANT)

    override suspend fun reserve(namespace: String, key: String, tenancy: ReservationTenancy): Boolean {
        val store = reservations ?: throw ScopeMisuse.State("Reserving a key needs a kernel with a ReservationStore.")
        val reservation = ReservationKey(namespace, key, tenancy == ReservationTenancy.SYSTEM)
        if (reserved?.contains(reservation) == true) {
            return true
        }
        // The aggregate owns the key, field by field, within its context and tenant. The
        // attempt holds a pending reservation by a hold of its own, so its compensation gives up its hold only and
        // never another attempt's of the same aggregate, nor a confirmed key.
        val owner = command.ref
        val scope = ReservationScope.of(owner, reservation)
        val hold = hold ?: UUID.randomUUID().toString().also { hold = it }
        val held = store.reserve(scope, key, owner, hold, reservationTtl) ?: return false
        if (!held.confirmed) {
            (reserved ?: ArrayList<ReservationKey>().also { reserved = it }) += reservation
            onAppendFailure { store.abandon(scope, key, owner, hold) }
            afterAppend { confirmed(store.confirm(scope, key, owner, committed), scope, key) }
        }
        return true
    }

    override fun release(namespace: String, key: String) = release(namespace, key, ReservationTenancy.TENANT)

    override fun release(namespace: String, key: String, tenancy: ReservationTenancy) {
        val store = reservations ?: throw ScopeMisuse.State("Releasing a key needs a kernel with a ReservationStore.")
        val reservation = ReservationKey(namespace, key, tenancy == ReservationTenancy.SYSTEM)
        if (released?.contains(reservation) == true) {
            return
        }
        (released ?: ArrayList<ReservationKey>().also { released = it }) += reservation
        val owner = command.ref
        val scope = ReservationScope.of(owner, reservation)
        afterAppend { store.release(scope, key, owner, committed) }
    }

    /** Logs a key the committed fact lost to another owner: confirming cannot get it back. */
    private fun confirmed(outcome: ConfirmOutcome, scope: ReservationScope, key: String) {
        if (outcome is ConfirmOutcome.Taken) {
            logger.error {
                "${command.ref} committed with $key in $scope, which ${outcome.by.owner} holds: the key is lost."
            }
            observer.reservationLost(command.ref.type.context)
        }
    }

    override fun onAppendFailure(compensation: suspend () -> Unit) {
        (compensations ?: ArrayList<suspend () -> Unit>().also { compensations = it }) += compensation
    }

    override fun afterAppend(action: suspend () -> Unit) {
        (afterAppendActions ?: ArrayList<suspend () -> Unit>().also { afterAppendActions = it }) += action
    }

    /**
     * Runs the compensations, latest first; their failures join [cause] when there is one, else they are logged.
     * They run whether or not the attempt is cancelled: an attempt cancelled while deciding, say a
     * saga's lane stopped by a handover, must still undo what its decide functions did outside the log.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun compensate(cause: Throwable? = null) {
        val registered = compensations ?: return
        withContext(NonCancellable) {
            registered.asReversed().forEach { compensation ->
                try {
                    compensation()
                } catch (failure: Throwable) {
                    cause?.addSuppressed(failure) ?: logger.warn(failure) { "A compensation failed." }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun afterAppend(version: Int) {
        committed = version
        afterAppendActions?.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                logger.warn(failure) { "An after-append action failed; the events are committed." }
            }
        }
    }
}

/** The kernel's logger: an attempt's warnings are the kernel's. */
private val logger = KotlinLogging.logger(Kernel::class.java.name)
