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

package me.ahoo.wow.ops.api.parked

import jakarta.validation.constraints.PositiveOrZero
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.InternalCommand
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.ledger.Ledger

/**
 * The owning service publishes its entry as it stands: the [ParkedEntry] its runtime sends, as the
 * member [Ledger.ENTRY]. It creates the entry's aggregate the first time. Only the services' ledgers send it, through
 * the ops command topic: it is internal, and no public route serves it.
 */
@AllowCreate
@InternalCommand
@Summary("Record a parked entry")
data class RecordParked(val entry: ParkedEntry)

/**
 * The owning service publishes that its entry is resolved: the [ParkedResolution], as the member [Ledger.RESOLUTION].
 * Internal, as [RecordParked] is.
 */
@InternalCommand
@Summary("Record a resolved entry")
data class RecordResolved(val resolution: ParkedResolution)

/**
 * The owning service refused the ledger's system command [action] (`wow.ledger.RetryParked`, ...) the ops saga sent it for
 * an operator's request on the entry, with the error [code] and [reason] of its rejection: its receipt
 * came back to the saga, which records it here, so the console shows why the request came to nothing. Only the saga
 * sends it: internal.
 */
@InternalCommand
@Summary("Record an action the owning service refused")
data class RecordActionRefused(val action: String, val code: String, val reason: String)

/*
 * An operator's actions on a parked entry: each is recorded on the entry's `parked` aggregate
 * and sent to the owning service as the ledger's system command of the same name (`wow.ledger.*`), which runs it.
 */

/**
 * Handles the parked aggregate again from where it is parked (`wow.ledger.RetryParked`): the parking at [failedAt], the
 * entry's `failedAt` as the console showed it, when given; another parking refuses it as stale.
 */
@Summary("Retry a parked entry")
data class Retry(val reason: String? = null, val failedAt: Long? = null)

/**
 * Resolves the entry without handling what was parked (`wow.ledger.SkipParked`): the parking at [failedAt], as for
 * [Retry].
 */
@Summary("Skip a parked entry")
data class Skip(val reason: String? = null, val failedAt: Long? = null)

/**
 * Marks the entry [recoverable] (`wow.ledger.MarkParkedRecoverable`, 9.x `MarkRecoverable`): `recoverable`, `unrecoverable`
 * or `unknown`; an unrecoverable entry is not retried automatically. The parking at [failedAt], as for [Retry]: left
 * out, it marks whichever parking the entry holds, one the caller may not have seen; the console always sends it.
 */
@Summary("Mark a parked entry recoverable or not")
data class MarkRecoverable(val recoverable: String, val failedAt: Long? = null)

/**
 * Applies a retry spec to the entry (`wow.ledger.ApplyParkedRetrySpec`, 9.x `ApplyRetrySpec`): at most [maxRetries]
 * automatic retries, the first [initialBackoff] after the failure, each claiming the entry for [claimTtl] (`PT3M` or
 * `3m`). A member left out keeps the value the entry is retried by now. The host refuses a negative count and a
 * duration Kotlin cannot read at admission (`wow.command.invalid`, each under its member's path). The parking at
 * [failedAt], as for [MarkRecoverable].
 */
@Summary("Apply a retry spec to a parked entry")
data class ApplyRetrySpec(
    @field:PositiveOrZero val maxRetries: Int? = null,
    @field:RetryDuration val initialBackoff: String? = null,
    @field:RetryDuration val claimTtl: String? = null,
    val failedAt: Long? = null,
)
