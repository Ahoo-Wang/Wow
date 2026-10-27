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

package me.ahoo.wow.ops.domain.parked

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.ops.api.OpsContext
import me.ahoo.wow.ops.api.parked.ActionRefused
import me.ahoo.wow.ops.api.parked.ApplyRetrySpec
import me.ahoo.wow.ops.api.parked.ApplyRetrySpecRequested
import me.ahoo.wow.ops.api.parked.EntryAggregate
import me.ahoo.wow.ops.api.parked.EntryRetrySpec
import me.ahoo.wow.ops.api.parked.MarkRecoverable
import me.ahoo.wow.ops.api.parked.MarkRecoverableRequested
import me.ahoo.wow.ops.api.parked.ParkedEntry
import me.ahoo.wow.ops.api.parked.ParkedRecorded
import me.ahoo.wow.ops.api.parked.ParkedResolution
import me.ahoo.wow.ops.api.parked.ParkedResolved
import me.ahoo.wow.ops.api.parked.ParkedStatus
import me.ahoo.wow.ops.api.parked.RecordActionRefused
import me.ahoo.wow.ops.api.parked.RecordParked
import me.ahoo.wow.ops.api.parked.RecordResolved
import me.ahoo.wow.ops.api.parked.Resume
import me.ahoo.wow.ops.api.parked.ResumeRequested
import me.ahoo.wow.ops.api.parked.Skip
import me.ahoo.wow.ops.api.parked.SkipRequested
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.test.AggregateSpec
import me.ahoo.wow.test.aggregateSpec
import org.junit.jupiter.api.Test

/** The ops view of one parked entry (decision D141): the records of its service, and an operator's actions. */
class ParkedSpec {
    private fun parkedSpec(block: AggregateSpec<ParkedState>.() -> Unit) =
        aggregateSpec(parkedModel(OpsContext.NAME), id = ID, block = block)

    @Test
    fun `a record creates the entry, and one that says nothing new changes nothing`() {
        parkedSpec {
            whenCommand(RecordParked(ENTRY)) {
                expectEvents(ParkedRecorded(ENTRY))
                expectState {
                    service.assert().isEqualTo("eshop-payment-service")
                    context.assert().isEqualTo("eshop-payment")
                    subscription.assert().isEqualTo(SUBSCRIPTION)
                    aggregate.assert().isEqualTo(AGGREGATE)
                    fromVersion.assert().isEqualTo(1)
                    fromPosition.assert().isNull()
                    reason.assert().isEqualTo("java.net.ConnectException: The gateway is down.")
                    errorType.assert().isEqualTo("java.net.ConnectException")
                    failedAt.assert().isEqualTo(100L)
                    retries.assert().isEqualTo(0)
                    retryAt.assert().isNull()
                    retrySpec.maxRetries.assert().isEqualTo(10)
                    recoverable.assert().isEqualTo(ParkedEntry.UNKNOWN)
                    request.assert().isNull()
                    status.assert().isEqualTo(ParkedStatus.SCHEDULED)
                    nextRetryAt.assert().isEqualTo(280L)
                    recordedAt.assert().isEqualTo(110L)
                    resolved.assert().isFalse()
                }
            }
            // A scan sends it again later.
            whenCommand(RecordParked(entry("recordedAt" to 200))) { expectUnchanged() }
            // A record published before the one the entry holds.
            whenCommand(RecordParked(entry("retries" to 5, "recordedAt" to 50))) { expectUnchanged() }
            val exhausted = entry("retries" to 10, "status" to ParkedStatus.EXHAUSTED, "recordedAt" to 300)
            whenCommand(RecordParked(exhausted)) {
                expectEvents(ParkedRecorded(exhausted))
                expectState { status.assert().isEqualTo(ParkedStatus.EXHAUSTED) }
            }
        }
    }

    @Test
    fun `a resolution resolves the entry once, and a late record of its parking changes nothing`() {
        parkedSpec {
            given(ParkedRecorded(ENTRY))
            // The resolution of an earlier parking of the aggregate.
            whenCommand(RecordResolved(resolution(failedAt = 50))) { expectUnchanged() }
            whenCommand(RecordResolved(RESOLUTION)) {
                expectEvents(ParkedResolved(ParkedResolution.RESUMED, 100, 400))
                expectState {
                    resolved.assert().isTrue()
                    status.assert().isEqualTo(ParkedStatus.RESOLVED)
                    outcome.assert().isEqualTo(ParkedResolution.RESUMED)
                    resolvedAt.assert().isEqualTo(400L)
                }
            }
            whenCommand(RecordResolved(resolution(outcome = ParkedResolution.RESOLVED))) { expectUnchanged() }
            whenCommand(RecordParked(entry("recordedAt" to 500))) { expectUnchanged() }
            whenCommand(Resume()) { expectRejected(ParkedRejections.RESOLVED) }
            // The aggregate parks again: a new parking.
            val again = entry("failedAt" to 600, "recordedAt" to 610)
            whenCommand(RecordParked(again)) {
                expectEvents(ParkedRecorded(again))
                expectState {
                    resolved.assert().isFalse()
                    outcome.assert().isNull()
                }
            }
        }
    }

    @Test
    fun `the error type is what the reason names before its message, else the whole reason`() {
        ParkedState.errorTypeOf("wow.subscription.unrecoverable: offset 3 is gone").assert()
            .isEqualTo("wow.subscription.unrecoverable")
        ParkedState.errorTypeOf("java.lang.IllegalStateException: a: b").assert()
            .isEqualTo("java.lang.IllegalStateException")
        ParkedState.errorTypeOf("The gateway is down.").assert().isEqualTo("The gateway is down.")
        parkedSpec {
            given(ParkedRecorded(ENTRY))
            val other = entry("reason" to "The gateway is down again.", "recordedAt" to 300)
            whenCommand(RecordParked(other)) {
                expectState { errorType.assert().isEqualTo("The gateway is down again.") }
            }
        }
    }

    @Test
    fun `a resolution its service did not date resolves the entry`() {
        parkedSpec {
            given(ParkedRecorded(ENTRY))
            whenCommand(RecordResolved(resolution(failedAt = null, outcome = ParkedResolution.NOT_PARKED))) {
                expectEvents(ParkedResolved(ParkedResolution.NOT_PARKED, null, 400))
            }
        }
    }

    @Test
    fun `an operator's actions are recorded for the owning service`() {
        parkedSpec {
            given(ParkedRecorded(ENTRY))
            // Each names the parking it acts on, the one the entry records.
            whenCommand(Resume("fixed")) {
                expectEvents(ResumeRequested("fixed", 100))
                expectState { requested.assert().isEqualTo(ParkedState.RESUME) }
            }
            whenCommand(Skip(failedAt = 100)) {
                expectEvents(SkipRequested(failedAt = 100))
                expectState { requested.assert().isEqualTo(ParkedState.SKIP) }
            }
            whenCommand(MarkRecoverable("unrecoverable")) {
                expectEvents(MarkRecoverableRequested("UNRECOVERABLE"))
                expectState { requested.assert().isEqualTo(ParkedState.MARK_RECOVERABLE) }
            }
            whenCommand(MarkRecoverable("maybe")) { expectRejected(ParkedRejections.INVALID) }
            // A parking the operator saw before the one the entry records now is stale.
            whenCommand(Resume("fixed", failedAt = 99)) { expectRejected(ParkedRejections.STALE) }
            whenCommand(Skip(failedAt = 99)) { expectRejected(ParkedRejections.STALE) }
            whenCommand(ApplyRetrySpec(maxRetries = 3, initialBackoff = "PT1M", executionTimeout = "30s")) {
                expectEvents(ApplyRetrySpecRequested(3, "PT1M", "30s"))
                expectState { requested.assert().isEqualTo(ParkedState.APPLY_RETRY_SPEC) }
            }
            whenCommand(ApplyRetrySpec()) { expectEvents(ApplyRetrySpecRequested()) }
            whenCommand(ApplyRetrySpec(maxRetries = -1)) { expectRejected(ParkedRejections.INVALID) }
            whenCommand(ApplyRetrySpec(initialBackoff = "soon")) { expectRejected(ParkedRejections.INVALID) }
            whenCommand(ApplyRetrySpec(executionTimeout = "-1m")) { expectRejected(ParkedRejections.INVALID) }
            whenCommand(ApplyRetrySpec(executionTimeout = "INFINITE")) { expectRejected(ParkedRejections.INVALID) }
            // The service's next record answers the request.
            whenCommand(RecordParked(entry("request" to "RESUME", "recordedAt" to 700))) {
                expectState { requested.assert().isNull() }
            }
        }
    }

    @Test
    fun `a refusal of the owning service is recorded on the entry until the next request (D226)`() {
        parkedSpec {
            given(ParkedRecorded(ENTRY), ResumeRequested("fixed"))
            whenCommand(RecordActionRefused(Ledger.RESUME, "wow.auth.forbidden", "Not from the ops saga.")) {
                expectEvents(ActionRefused(Ledger.RESUME, "wow.auth.forbidden", "Not from the ops saga."))
                expectState {
                    requested.assert().isNull()
                    refusedAction.assert().isEqualTo(Ledger.RESUME)
                    refusalCode.assert().isEqualTo("wow.auth.forbidden")
                    refusalReason.assert().isEqualTo("Not from the ops saga.")
                }
            }
            // The service's next record leaves the refusal shown.
            whenCommand(RecordParked(entry("retries" to 1, "recordedAt" to 700))) {
                expectState { refusedAction.assert().isEqualTo(Ledger.RESUME) }
            }
            // A new request clears it.
            whenCommand(Skip()) {
                expectState {
                    requested.assert().isEqualTo(ParkedState.SKIP)
                    refusedAction.assert().isNull()
                    refusalCode.assert().isNull()
                    refusalReason.assert().isNull()
                }
            }
        }
    }

    @Test
    fun `a refusal of a resolved entry changes nothing`() {
        parkedSpec {
            given(ParkedRecorded(ENTRY), ParkedResolved(ParkedResolution.RESUMED, 100, 400))
            whenCommand(RecordActionRefused(Ledger.SKIP, "wow.auth.forbidden", "No.")) { expectUnchanged() }
        }
        // A resolution clears a refusal.
        parkedSpec {
            given(ParkedRecorded(ENTRY), ActionRefused(Ledger.SKIP, "wow.auth.forbidden", "No."))
            whenCommand(RecordResolved(RESOLUTION)) {
                expectState { refusedAction.assert().isNull() }
            }
        }
    }

    companion object {
        const val SUBSCRIPTION = "PaymentGatewaySaga.onPaymentRequested"
        val AGGREGATE = EntryAggregate("eshop-payment", "payment", "(0)", "alice-1")
        const val ID = "eshop-payment-service:$SUBSCRIPTION:eshop-payment.payment:(0):alice-1"

        /**
         * The fields of the entry the tests record. A [ParkedEntry] is a wire record whose constructor is Wow's own
         * (decision D216): the tests read it from its JSON, as the ops context receives it.
         */
        private val ENTRY_FIELDS: Map<String, Any?> = mapOf(
            "service" to "eshop-payment-service",
            "context" to "eshop-payment",
            "subscription" to SUBSCRIPTION,
            "aggregate" to AGGREGATE,
            "fromVersion" to 1,
            "reason" to "java.net.ConnectException: The gateway is down.",
            "failedAt" to 100,
            "retrySpec" to EntryRetrySpec(10, "PT3M", "PT2M"),
            "status" to ParkedStatus.SCHEDULED,
            "nextRetryAt" to 280,
            "recordedAt" to 110,
        )

        /** The entry with [changes] to its fields. */
        fun entry(vararg changes: Pair<String, Any?>): ParkedEntry =
            WowJson.mapper.convertValue(ENTRY_FIELDS + changes, ParkedEntry::class.java)

        val ENTRY = entry()
        fun resolution(failedAt: Long? = 100, outcome: String = ParkedResolution.RESUMED): ParkedResolution =
            ParkedResolution(
                service = "eshop-payment-service",
                context = "eshop-payment",
                subscription = SUBSCRIPTION,
                aggregate = AGGREGATE,
                failedAt = failedAt,
                outcome = outcome,
                resolvedAt = 400,
            )

        val RESOLUTION = resolution()
    }
}
