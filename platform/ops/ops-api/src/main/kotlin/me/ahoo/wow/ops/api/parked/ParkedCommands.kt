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

import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.ledger.LedgerEntry
import me.ahoo.wow.api.ledger.LedgerResolution

/**
 * The owning service publishes its entry as it stands (decision D141): the [LedgerEntry] its runtime sends, as the
 * member [Ledger.ENTRY]. It creates the entry's aggregate the first time.
 */
@AllowCreate
@Summary("Record a parked entry")
data class RecordParked(val entry: LedgerEntry)

/** The owning service publishes that its entry is resolved: the [LedgerResolution], as the member [Ledger.RESOLUTION]. */
@Summary("Record a resolved entry")
data class RecordResolved(val resolution: LedgerResolution)

/**
 * The owning service refused the ledger's system command [action] (`wow.ledger.Resume`, ...) the ops saga sent it for
 * an operator's request on the entry, with the error [code] and [reason] of its rejection (decision D226): its receipt
 * came back to the saga, which records it here, so the console shows why the request came to nothing.
 */
@Summary("Record an action the owning service refused")
data class RecordActionRefused(val action: String, val code: String, val reason: String)

/*
 * An operator's actions on a parked entry (decisions D141, D185): each is recorded on the entry's `parked` aggregate
 * and sent to the owning service as the ledger's system command of the same name (`wow.ledger.*`), which runs it.
 */

/** Handles the parked aggregate again from where it is parked (`wow.ledger.Resume`). */
@Summary("Resume a parked entry")
data class Resume(val reason: String? = null)

/** Resolves the entry without handling what was parked (`wow.ledger.Skip`). */
@Summary("Skip a parked entry")
data class Skip(val reason: String? = null)

/**
 * Marks the entry [recoverable] (`wow.ledger.MarkRecoverable`, 9.x `MarkRecoverable`): `RECOVERABLE`, `UNRECOVERABLE`
 * or `UNKNOWN`; an unrecoverable entry is not retried automatically.
 */
@Summary("Mark a parked entry recoverable or not")
data class MarkRecoverable(val recoverable: String)

/**
 * Applies a retry spec to the entry (`wow.ledger.ApplyRetrySpec`, 9.x `ApplyRetrySpec`): at most [maxRetries]
 * automatic retries, the first [minBackoff] after the failure, each allowed [executionTimeout] (`PT3M` or `3m`). A
 * member left out keeps the value the entry is retried by now.
 */
@Summary("Apply a retry spec to a parked entry")
data class ApplyRetrySpec(
    val maxRetries: Int? = null,
    val minBackoff: String? = null,
    val executionTimeout: String? = null,
)
