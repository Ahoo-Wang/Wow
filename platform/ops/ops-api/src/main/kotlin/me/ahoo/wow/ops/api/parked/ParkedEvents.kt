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

import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.ledger.LedgerEntry

/** The owning service published its entry as [entry] says: parked, as its ledger holds it now. */
@Summary("A parked entry was recorded")
data class ParkedRecorded(val entry: LedgerEntry)

/**
 * The owning service published that its entry is no longer parked, as of [resolvedAt]: the parking that failed at
 * [failedAt] came to [outcome] (`RESUMED`, `SKIPPED`, `RESOLVED` or `NOT_PARKED`).
 */
@Summary("A parked entry was resolved")
data class ParkedResolved(val outcome: String, val failedAt: Long? = null, val resolvedAt: Long)

/** An operator asked the owning service to resume the entry, for [reason]. */
@Summary("A resume was requested")
data class ResumeRequested(val reason: String? = null)

/** An operator asked the owning service to skip the entry, for [reason]. */
@Summary("A skip was requested")
data class SkipRequested(val reason: String? = null)

/** An operator asked the owning service to mark the entry [recoverable]. */
@Summary("A recoverable mark was requested")
data class MarkRecoverableRequested(val recoverable: String)

/** An operator asked the owning service to apply a retry spec to the entry; see [ApplyRetrySpec]. */
@Summary("A retry spec was requested")
data class ApplyRetrySpecRequested(
    val maxRetries: Int? = null,
    val minBackoff: String? = null,
    val executionTimeout: String? = null,
)

/** Where an entry is, as the ops view shows it: its ledger's status while parked, then [RESOLVED]. */
object ParkedStatus {
    const val SCHEDULED: String = "SCHEDULED"
    const val EXHAUSTED: String = "EXHAUSTED"
    const val UNRECOVERABLE: String = "UNRECOVERABLE"
    const val RESOLVED: String = "RESOLVED"
}
