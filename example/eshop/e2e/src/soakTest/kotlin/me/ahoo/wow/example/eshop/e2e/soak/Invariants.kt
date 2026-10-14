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

package me.ahoo.wow.example.eshop.e2e.soak

import java.util.concurrent.atomic.AtomicLong

/**
 * One invariant: its [name], what it holds ([description]), how many things were [checked] and the violations found,
 * each with its evidence. Only the first [MAX_KEPT] violations are kept; [violationCount] counts them all.
 */
class Invariant(val name: String, val description: String) {
    val checked = AtomicLong()
    private val violations = mutableListOf<Map<String, Any?>>()
    private var violationCount = 0L

    @Synchronized
    fun violate(evidence: Map<String, Any?>) {
        violationCount++
        if (violations.size < MAX_KEPT) violations += evidence
    }

    fun check(count: Long = 1) {
        checked.addAndGet(count)
    }

    @get:Synchronized
    val violated: Long get() = violationCount

    @Synchronized
    fun describe(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "description" to description,
        "ok" to (violationCount == 0L),
        "checked" to checked.get(),
        "violations" to violationCount,
        "evidence" to violations.toList(),
    )

    private companion object {
        const val MAX_KEPT = 100
    }
}

/** The invariants of C.1 (roadmap, decision D196), in the order the report lists them. */
class Invariants {
    val exactlyOnce = Invariant(
        "commands.exactly-once",
        "Every command a client was answered as committed (a commit or a duplicate) is in its context's log exactly " +
            "once by its request id; every request id of the soak in a log is one a client sent.",
    )
    val logContiguous = Invariant(
        "log.contiguous",
        "Each partition of each context's log holds the sequences 1..head once each, across its aggregate tables; " +
            "each aggregate holds the versions 1..max once each (no event lost or duplicated in the log).",
    )
    val noDuplicateEffects = Invariant(
        "events.no-duplicate-effects",
        "Each order, payment, basket and stock holds exactly the events its scenario calls for: none lost, none " +
            "repeated under another request id (a saga's command sent twice, say).",
    )
    val flowsComplete = Invariant(
        "flows.complete",
        "Every checked-out order settles once the load and the faults stop: paid and shipped, or cancelled for its " +
            "short stock or its declined payment; the stock adds up (on hand = received - shipped, nothing reserved).",
    )
    val checkpointsMonotonic = Invariant(
        "checkpoints.monotonic",
        "No subscription checkpoint (wow_checkpoint, sampled in every database) ever moves backwards.",
    )
    val parkedEmpty = Invariant(
        "parked.empty",
        "The failure ledger (wow_failure_ledger) of every database is empty at the end; an entry seen while the " +
            "soak ran is listed with its reason and the fault around it.",
    )
    val waitsAnswered = Invariant(
        "waits.answered",
        "Every request with a Wow-Wait is answered within its timeout and a grace: a result, or wow.wait.timeout " +
            "with the last hop reached; none is left hanging.",
    )
    val readModelsConverge = Invariant(
        "read-models.converge",
        "Once settled, every aggregate's snapshot is at its last logged version, and a sample of snapshots equal " +
            "a replay of their log (the trace from version 1).",
    )

    val all: List<Invariant> = listOf(
        exactlyOnce, logContiguous, noDuplicateEffects, flowsComplete,
        checkpointsMonotonic, parkedEmpty, waitsAnswered, readModelsConverge,
    )
}
