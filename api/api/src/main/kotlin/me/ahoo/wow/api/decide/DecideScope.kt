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

package me.ahoo.wow.api.decide

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.id.Ids
import me.ahoo.wow.api.schedule.ScheduleContext
import me.ahoo.wow.api.schedule.ScheduleRule

/**
 * What a decide function (or an after hook) can ask of the kernel besides returning its decision.
 *
 * A decide function may act on other systems, such as reserving a unique key. [onAppendFailure] undoes such an act
 * when the command's events are surely not appended: the decision rejects, the version conflicts and the command is
 * decided again, or deciding fails. It does not run when whether the append committed is unknown, and it cannot run
 * if the process dies; an act that must not leak then needs its own expiry. Reserved keys ([reserve], [release]) are
 * unique within the aggregate's type and tenant unless reserved with [ReservationTenancy.SYSTEM].
 *
 * A decide function may also schedule a command for later: [schedule] and [cancelSchedule] add
 * the system events `wow.ScheduleSet` and `wow.ScheduleCancelled` to the events the command commits, atomically with
 * them, and the runtime sends the command each time it is due. A decision that only schedules commits those events.
 */
interface DecideScope {
    /** The command being decided, with what admission learned about its request. */
    val command: Command<*>

    /**
     * The ids this decision takes: the node's global and aggregate id generators, fixed ids in the
     * test DSL. A decision decided again, after a version conflict, takes new ones.
     */
    val ids: Ids

    /** Adds [value] under [key] to what the caller is answered once the events are appended. */
    fun putResult(key: String, value: Any)

    /**
     * Refuses the command with the rejection [code] and [message], as returning
     * `Decision.reject(code, message)` does: it throws a [RejectedException], which the kernel answers as that
     * rejection. [message] is shown to the caller.
     */
    fun reject(code: String, message: String): Nothing = reject(code, message, emptyMap())

    /** Refuses the command with the rejection [code], [message] and structured [extensions]; see [reject]. */
    fun reject(code: String, message: String, extensions: Map<String, Any?>): Nothing =
        throw RejectedException(Rejection.of(code, message, extensions))

    /** Registers [compensation] to run when this attempt's events are surely not appended. */
    fun onAppendFailure(compensation: suspend () -> Unit)

    /** Registers [action] to run once this attempt's events are appended. A failure of it is logged, not raised. */
    fun afterAppend(action: suspend () -> Unit)

    /**
     * Reserves [key] in [namespace] for this command's aggregate, such as a unique user name; false
     * when another aggregate holds it. The key is unique among the aggregates of this aggregate's type, within its
     * tenant ([ReservationTenancy.TENANT]): another tenant, or an aggregate of another type, may hold the same key
     * (a type's reservations live with its log). A key unique across types, such as an e-mail address
     * that users and organizations share, is modelled as an aggregate of its own that holds the keys, which the other
     * aggregates claim through it.
     *
     * The reservation is pending, with an expiry, until the events are appended; then it is confirmed. When they are
     * surely not appended this attempt gives its reservation up, without touching another attempt's of the same
     * aggregate, and if the process dies first it expires. A key the aggregate already holds confirmed
     * stays held whatever this attempt does. A rename reserves the new key and [release]s the old one in one
     * decision.
     *
     * It is a `suspend` function, so a Java decide function cannot call it (a blocking bridge is
     * planned for 10.x): a decide function that reserves keys is written in Kotlin.
     */
    suspend fun reserve(namespace: String, key: String): Boolean

    /**
     * Reserves [key] in [namespace] for this command's aggregate as [reserve] does, unique among the aggregates
     * [tenancy] says: [ReservationTenancy.SYSTEM] makes it unique across the type's tenants.
     */
    suspend fun reserve(namespace: String, key: String, tenancy: ReservationTenancy): Boolean

    /**
     * Releases [key] in [namespace], which this command's aggregate holds, once the events are appended:
     * another aggregate may then reserve it. The release commits with the events: it is recorded with them and
     * applied again after a crash, as a confirmation is; nothing is released when they are not
     * appended, or by a decision that appends no event. A key the aggregate does not hold stays as it is.
     *
     * Deleting an aggregate releases none of its keys, so a restored aggregate still holds them: its delete command
     * releases the keys it should give up.
     */
    fun release(namespace: String, key: String)

    /** Releases [key] in [namespace] as [release] does, the key reserved with [tenancy]. */
    fun release(namespace: String, key: String, tenancy: ReservationTenancy)

    /**
     * Schedules [command] to be sent to [target], this aggregate by default, as [rule] says: at an
     * instant, after a delay counted from this commit, or by a cron expression. [key] names the schedule within this
     * aggregate: scheduling a key again replaces it (a reschedule, or a new rule), and [cancelSchedule] cancels it.
     * Within one decision the last call for a key wins.
     *
     * The schedule commits with this command's events (as the system event `wow.ScheduleSet`), or not at all. Each
     * time it is due the command is sent at least once, not before its time, possibly a little later (after a handover
     * or a backlog), and decided once: its request id is the timer's id and the slot ([ScheduleContext], which the
     * target reads from [Command.schedule]). The target decides it on its state then; a rejection is a normal guard,
     * not a failure. Its scope is this commit's, its causation this commit. A target of another context needs its
     * context's Manifest.
     */
    fun schedule(key: String, rule: ScheduleRule, command: Any, target: AggregateRef)

    /** Schedules [command] for this aggregate under [key] as [rule] says; see [schedule] of a target. */
    fun schedule(key: String, rule: ScheduleRule, command: Any) = schedule(key, rule, command, this.command.ref)

    /**
     * Cancels the schedule this aggregate keeps under [key], scheduled by an earlier command or this one (the system
     * event `wow.ScheduleCancelled`); nothing when there is none. A command already sent is not recalled: its target's state guards it.
     */
    fun cancelSchedule(key: String)
}
