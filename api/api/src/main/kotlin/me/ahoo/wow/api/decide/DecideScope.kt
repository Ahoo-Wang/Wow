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
 * if the process dies; an act that must not leak then needs its own expiry.
 *
 * A decide function may also schedule a command for later (decisions D166, D198): [schedule] and [cancelSchedule] add
 * the system events `wow.ScheduleSet` and `wow.ScheduleCancelled` to the events the command commits, atomically with
 * them, and the runtime sends the command each time it is due. A decision that only schedules commits those events.
 */
interface DecideScope {
    /** The command being decided, with what admission learned about its request. */
    val command: Command<*>

    /**
     * The ids this decision takes (decision D171): the node's global and aggregate id generators, fixed ids in the
     * test DSL. A decision decided again, after a version conflict, takes new ones.
     */
    val ids: Ids

    /** Adds [value] under [key] to what the caller is answered once the events are appended. */
    fun putResult(key: String, value: Any)

    /** Registers [compensation] to run when this attempt's events are surely not appended. */
    fun onAppendFailure(compensation: suspend () -> Unit)

    /** Registers [action] to run once this attempt's events are appended. A failure of it is logged, not raised. */
    fun afterAppend(action: suspend () -> Unit)

    /**
     * Reserves [key] in [namespace] for this command's aggregate, such as a unique user name (decision D38); false
     * when another aggregate holds it. The reservation is pending, with an expiry, until the events are appended; then
     * it is confirmed. When they are surely not appended it is released, and if the process dies first it expires
     * (finding R8). A key the aggregate already holds confirmed stays held whatever this attempt does. Releasing or
     * moving a confirmed key, as a rename does, is a saga's work.
     */
    suspend fun reserve(namespace: String, key: String): Boolean

    /**
     * Schedules [command] to be sent to [target], this aggregate by default, as [rule] says (decision D198): at an
     * instant, after a delay counted from this commit, or by a cron expression. [key] names the schedule within this
     * aggregate: scheduling a key again replaces it (a reschedule, or a new rule), and [cancelSchedule] cancels it.
     * Within one decision the last call for a key wins.
     *
     * The schedule commits with this command's events (as the system event `wow.ScheduleSet`), or not at all. Each
     * time it is due the command is sent at least once, not before its time, possibly a little later (after a handover
     * or a backlog), and decided once: its request id is the timer's id and the slot ([ScheduleContext], which the
     * target reads from [Command.schedule]). The target decides it on its state then; a rejection is a normal guard,
     * not a failure. Its scope is this commit's, its causation this commit. A target of another context needs its
     * context's Manifest (decision D139).
     */
    fun schedule(key: String, rule: ScheduleRule, command: Any, target: AggregateRef = this.command.ref)

    /**
     * Cancels the schedule this aggregate keeps under [key], scheduled by an earlier command or this one (the system
     * event `wow.ScheduleCancelled`); nothing when there is none. A command already sent is not recalled: its target's state guards it.
     */
    fun cancelSchedule(key: String)
}
