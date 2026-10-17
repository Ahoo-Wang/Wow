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

package me.ahoo.wow.migrate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.decide.ReservationTenancy
import me.ahoo.wow.spi.reservation.ConfirmOutcome
import me.ahoo.wow.spi.reservation.ReservationScope
import me.ahoo.wow.spi.reservation.ReservationStore

/** The 9.x `PrepareKey` [mapping] whose keys instances of [aggregate] own. */
class ReservationPlan(val aggregate: AggregatePlan, val mapping: ReservationMapping) {
    /**
     * The V10 owner and scope of the confirmed 9.x [key] (decision D225): the instance whose id the value holds, in
     * the tenant its 9.x streams record, which [source] finds; the key's tenant is the owner's, or the system tenant
     * for a [ReservationTenancy.SYSTEM] mapping. Why it has none when the value holds no id, no stream has the id, or
     * streams of several tenants do.
     */
    fun ownerOf(key: NineXPreparedKey, source: NineXEventSource): KeyOwner {
        val id = key.ownerId(mapping.ownerId) ?: return KeyOwner.Unresolved(
            if (mapping.ownerId == null) "its value is not an id" else "its value has no id at ${mapping.ownerId}",
        )
        val tenants = source.tenantsOf(aggregate.source, id)
        val tenantId = tenants.singleOrNull() ?: return KeyOwner.Unresolved(
            if (tenants.isEmpty()) {
                "no 9.x stream of ${aggregate.source} has the id $id"
            } else {
                "the id $id of ${aggregate.source} is in the tenants $tenants"
            },
        )
        val owner = aggregate.target.ref(tenantId, id)
        val scopeTenant = if (mapping.tenancy == ReservationTenancy.SYSTEM) ReservationScope.SYSTEM_TENANT else tenantId
        return KeyOwner.Found(owner, ReservationScope.of(aggregate.target.context, scopeTenant, mapping.namespace))
    }
}

/** The V10 owner of a 9.x key, or why it has none. */
sealed interface KeyOwner {
    /** The key is reserved in [scope] for [owner]. */
    data class Found(val owner: AggregateRef, val scope: ReservationScope) : KeyOwner

    /** No single V10 owner, for [reason]: the key is not imported. */
    data class Unresolved(val reason: String) : KeyOwner
}

/**
 * The reservation import of one 9.x `PrepareKey` since it started: the confirmed keys [imported] and those [already]
 * reserved alike in V10, the [pending] and [expired] keys skipped, and the [unresolved] ones without a single owner.
 */
data class ReservationProgress(
    val name: String,
    val imported: Long,
    val already: Long,
    val pending: Long,
    val expired: Long,
    val unresolved: Long,
    val done: Boolean,
) {
    fun render(): String =
        "PrepareKey $name reservations${if (done) " done" else ""}: ${imported + already + pending + expired + unresolved} " +
            "keys, $imported imported, $already already there, $pending pending and $expired expired skipped, " +
            "$unresolved without an owner"
}

/**
 * Imports the 9.x `PrepareKey` reservations into the V10 reservation store (decisions D38, D225, D227 ③, finding
 * A4-8), after the event streams of the aggregate that owns them, so a key 9.x holds is not free in V10 at the
 * cut-over:
 *
 * - **Confirmed keys only.** A key 9.x prepared forever is confirmed for its owner ([ReservationStore.confirm]); one
 *   prepared with a time to live is a decision still in flight, or one that never committed, and is skipped, as is an
 *   expired one.
 * - **Owner and tenant.** The owner is the instance whose id the 9.x value holds, in the tenant its 9.x streams record
 *   ([ReservationPlan.ownerOf]); a key whose owner is not found is reported and not imported.
 * - **Idempotent.** A key V10 already holds confirmed for the same owner is skipped; one another owner holds stops the
 *   import with a [MigrationException] naming both owners.
 * - **Checkpointed.** Keys are walked in key order, a page of [ImportOptions.pageSize] at a time,
 *   [ImportOptions.concurrency] at a time, and the checkpoint ([ReservationCheckpoint]) moves after each page;
 *   [ImportOptions.restart] walks every key again. A dry run reads and resolves every key and writes nothing.
 */
@Suppress("LongParameterList")
class ReservationImporter(
    private val source: NineXEventSource,
    private val reservations: NineXReservationSource,
    private val plan: MigrationPlan,
    private val store: () -> ReservationStore,
    private val checkpoints: CheckpointRepository,
    private val options: ImportOptions = ImportOptions(),
    private val progress: (ReservationProgress) -> Unit = {},
    private val report: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Imports the keys of the 9.x `PrepareKey` [name] not yet imported, and marks its import complete. */
    suspend fun importAll(name: String): ReservationProgress {
        val reservationPlan = plan.reservations[name]
            ?: throw MigrationException("migration-map.json does not map the 9.x PrepareKey $name.")
        // A dry run of the streams records no checkpoint: it reads the keys whatever the streams' import stands at.
        if (!options.dryRun && checkpoints.load().of(reservationPlan.aggregate.source)?.completed != true) {
            throw MigrationException(
                "The full import of ${reservationPlan.aggregate.source} has not completed: import its streams before " +
                    "the PrepareKey $name.",
            )
        }
        var checkpoint = checkpoints.load().reservationsOf(name)?.takeUnless { options.restart } ?: ReservationCheckpoint()
        val permits = Semaphore(options.concurrency)
        while (!checkpoint.completed) {
            val page = withContext(Dispatchers.IO) { reservations.keys(name, checkpoint.after, options.pageSize) }
            checkpoint = if (page.isEmpty()) {
                checkpoint.copy(completed = true)
            } else {
                val outcomes = coroutineScope {
                    page.map { key -> async { permits.withPermit { import(reservationPlan, key) } } }.awaitAll()
                }
                checkpoint.counted(page.last().key, outcomes)
            }
            checkpoints.save(checkpoints.load().with(name, checkpoint))
            if (!checkpoint.completed) {
                progress(checkpoint.progress(name))
            }
        }
        return checkpoint.progress(name).also(progress)
    }

    private suspend fun import(reservationPlan: ReservationPlan, key: NineXPreparedKey): KeyOutcome {
        if (key.expired(clock())) {
            return KeyOutcome.EXPIRED
        }
        if (!key.confirmed) {
            return KeyOutcome.PENDING
        }
        val owner = withContext(Dispatchers.IO) { reservationPlan.ownerOf(key, source) }
        if (owner is KeyOwner.Unresolved) {
            report("UNRESOLVED PrepareKey $key: ${owner.reason}; the key is not imported")
            return KeyOutcome.UNRESOLVED
        }
        owner as KeyOwner.Found
        if (options.dryRun) {
            return KeyOutcome.IMPORTED
        }
        val reservations = store()
        val held = reservations.find(owner.scope, key.key)
        if (held != null && held.confirmed && held.owner == owner.owner) {
            return KeyOutcome.ALREADY
        }
        val outcome = reservations.confirm(owner.scope, key.key, owner.owner)
        if (outcome is ConfirmOutcome.Taken) {
            throw MigrationException(
                "The 9.x PrepareKey $key of ${owner.owner} is held in V10 by ${outcome.by.owner} " +
                    "(${owner.scope}): release one of them, or map the PrepareKey to another namespace or tenancy.",
            )
        }
        return KeyOutcome.IMPORTED
    }

    private fun ReservationCheckpoint.counted(after: String, outcomes: List<KeyOutcome>): ReservationCheckpoint {
        fun count(outcome: KeyOutcome) = outcomes.count { it == outcome }.toLong()
        return copy(
            after = after,
            imported = imported + count(KeyOutcome.IMPORTED),
            already = already + count(KeyOutcome.ALREADY),
            pending = pending + count(KeyOutcome.PENDING),
            expired = expired + count(KeyOutcome.EXPIRED),
            unresolved = unresolved + count(KeyOutcome.UNRESOLVED),
        )
    }

    private fun ReservationCheckpoint.progress(name: String) =
        ReservationProgress(name, imported, already, pending, expired, unresolved, completed)

    private enum class KeyOutcome { IMPORTED, ALREADY, PENDING, EXPIRED, UNRESOLVED }
}

/**
 * The reservation comparison of `compare` (finding A4-8): every confirmed 9.x key has a confirmed V10 reservation of
 * the same owner. Counts the keys [compared], those that [differ] (missing, pending or another owner's in V10) and
 * those [unresolved] (no single owner, so not imported).
 */
data class ReservationComparisonResult(val name: String, val compared: Long, val differ: Long, val unresolved: Long) {
    val passed: Boolean get() = differ == 0L

    fun render(): String =
        "PrepareKey $name compared: $compared confirmed keys, $differ differ, $unresolved without an owner"
}

/** Compares the 9.x `PrepareKey`s with the V10 reservations, every key on every run. */
class ReservationComparison(
    private val source: NineXEventSource,
    private val reservations: NineXReservationSource,
    private val plan: MigrationPlan,
    private val store: () -> ReservationStore,
    private val pageSize: Int,
    private val report: (String) -> Unit = {},
) {
    suspend fun compare(name: String): ReservationComparisonResult {
        val reservationPlan = plan.reservations[name]
            ?: throw MigrationException("migration-map.json does not map the 9.x PrepareKey $name.")
        var result = ReservationComparisonResult(name, 0, 0, 0)
        var after: String? = null
        do {
            val page = withContext(Dispatchers.IO) { reservations.keys(name, after, pageSize) }
            page.filter { it.confirmed }.forEach { key -> result = compare(reservationPlan, key, result) }
            after = page.lastOrNull()?.key
        } while (page.isNotEmpty())
        return result.also { report(it.render()) }
    }

    private suspend fun compare(
        reservationPlan: ReservationPlan,
        key: NineXPreparedKey,
        result: ReservationComparisonResult,
    ): ReservationComparisonResult {
        val owner = withContext(Dispatchers.IO) { reservationPlan.ownerOf(key, source) }
        if (owner is KeyOwner.Unresolved) {
            return result.copy(unresolved = result.unresolved + 1)
        }
        owner as KeyOwner.Found
        val held = store().find(owner.scope, key.key)
        val v10 = when {
            held == null -> "(none)"
            held.owner != owner.owner -> held.owner.toString()
            !held.confirmed -> "${held.owner} pending"
            else -> return result.copy(compared = result.compared + 1)
        }
        report("DIFFERS PrepareKey $key: 9.x ${owner.owner}, V10 $v10")
        return result.copy(compared = result.compared + 1, differ = result.differ + 1)
    }
}
