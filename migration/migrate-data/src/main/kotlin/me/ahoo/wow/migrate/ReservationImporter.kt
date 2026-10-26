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
        return KeyOwner.Found(owner, ReservationScope.of(aggregate.target, scopeTenant, mapping.namespace))
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
    val released: Long = 0,
) {
    fun render(): String =
        "PrepareKey $name reservations${if (done) " done" else ""}: ${imported + already + pending + expired + unresolved} " +
            "keys, $imported imported, $already already there, $pending pending and $expired expired skipped, " +
            "$unresolved without an owner" +
            if (released > 0) "; $released imported keys released, 9.x no longer confirming them to that owner" else ""
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
 * - **Convergent** (finding R2.4-2). 9.x keeps serving while the migration runs: a key it released (`rollback`) or
 *   gave to another owner (`reprepare`) after an import confirmed it must not stay held in V10. The import records
 *   each key it confirms ([ImportedKeys]) before confirming it; a walk that starts from the first key (the first one,
 *   or the final `--restart`) first releases every recorded key V10 still holds for the recorded owner that 9.x no
 *   longer confirms to that owner, then confirms the keys 9.x holds now, so a key that moved is confirmed for its new
 *   owner. A key V10 holds without such a record, which V10 took itself, is never released.
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
    private val imported: ImportedKeys = InMemoryImportedKeys(),
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
        if (!options.dryRun && checkpoint.after == null && !checkpoint.completed) {
            // Saved first: a run stopped while it converges walks from the first key again, and so confirms the keys
            // that moved to another owner after it released them.
            checkpoints.update { it.with(name, checkpoint) }
            checkpoint = checkpoint.copy(released = converge(name, reservationPlan))
        }
        val permits = Semaphore(options.concurrency)
        while (!checkpoint.completed) {
            val page = withContext(Dispatchers.IO) { reservations.keys(name, checkpoint.after, options.pageSize) }
            checkpoint = if (page.isEmpty()) {
                checkpoint.copy(completed = true)
            } else {
                val resolved = coroutineScope {
                    page.map { key -> async { permits.withPermit { resolve(reservationPlan, key) } } }.awaitAll()
                }
                val confirming = resolved.filterIsInstance<KeyStep.Confirm>()
                imported.add(confirming.map { ImportedKey.of(name, it.key.key, it.owner.scope, it.owner.owner) })
                coroutineScope {
                    confirming.map { step -> async { permits.withPermit { confirm(step) } } }.awaitAll()
                }
                checkpoint.counted(page.last().key, resolved.map { (it as? KeyStep.Done)?.outcome ?: KeyOutcome.IMPORTED })
            }
            checkpoints.update { it.with(name, checkpoint) }
            if (!checkpoint.completed) {
                progress(checkpoint.progress(name))
            }
        }
        return checkpoint.progress(name).also(progress)
    }

    /**
     * Releases each key the import confirmed that V10 still holds for the recorded owner and 9.x no longer confirms
     * to that owner, and forgets the recorded keys V10 no longer holds for theirs; returns how many it released.
     */
    private suspend fun converge(name: String, reservationPlan: ReservationPlan): Long {
        val reservations = store()
        var released = 0L
        imported.retain(name) { record ->
            when (reservationPlan.standing(record, reservations, this.reservations, source)) {
                Standing.GONE -> false
                Standing.CURRENT -> true
                Standing.STALE -> {
                    reservations.release(record.scope(), record.key, record.owner(), ReservationStore.ANY_VERSION)
                    report(
                        "RELEASED PrepareKey $name/${record.key} of ${record.owner()}: 9.x no longer confirms it to them"
                    )
                    released++
                    false
                }
            }
        }
        return released
    }

    /** What to do with [key]: count it, or confirm it for its owner. */
    private suspend fun resolve(reservationPlan: ReservationPlan, key: NineXPreparedKey): KeyStep {
        if (key.expired(clock())) {
            return KeyStep.Done(KeyOutcome.EXPIRED)
        }
        if (!key.confirmed) {
            return KeyStep.Done(KeyOutcome.PENDING)
        }
        val owner = withContext(Dispatchers.IO) { reservationPlan.ownerOf(key, source) }
        if (owner is KeyOwner.Unresolved) {
            report("UNRESOLVED PrepareKey $key: ${owner.reason}; the key is not imported")
            return KeyStep.Done(KeyOutcome.UNRESOLVED)
        }
        owner as KeyOwner.Found
        if (options.dryRun) {
            return KeyStep.Done(KeyOutcome.IMPORTED)
        }
        val held = store().find(owner.scope, key.key)
        if (held != null && held.confirmed && held.owner == owner.owner) {
            return KeyStep.Done(KeyOutcome.ALREADY)
        }
        return KeyStep.Confirm(key, owner)
    }

    private suspend fun confirm(step: KeyStep.Confirm) {
        val (key, owner) = step
        val outcome = store().confirm(owner.scope, key.key, owner.owner, ReservationStore.UNVERSIONED)
        if (outcome is ConfirmOutcome.Taken) {
            throw MigrationException(
                "The 9.x PrepareKey $key of ${owner.owner} is held in V10 by ${outcome.by.owner} " +
                    "(${owner.scope}), which this import did not confirm: release one of them, or map the PrepareKey " +
                    "to another namespace or tenancy.",
            )
        }
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
        ReservationProgress(name, imported, already, pending, expired, unresolved, completed, released)

    private enum class KeyOutcome { IMPORTED, ALREADY, PENDING, EXPIRED, UNRESOLVED }

    private sealed interface KeyStep {
        data class Done(val outcome: KeyOutcome) : KeyStep

        data class Confirm(val key: NineXPreparedKey, val owner: KeyOwner.Found) : KeyStep
    }
}

/** Where a key the import confirmed ([ImportedKey]) stands now. */
internal enum class Standing {
    /** V10 no longer holds it confirmed for the recorded owner: released, or never confirmed by a stopped run. */
    GONE,

    /** V10 holds it for the recorded owner, and 9.x still confirms it to that owner in that scope. */
    CURRENT,

    /** V10 holds it for the recorded owner, which 9.x no longer confirms it to: released or moved in 9.x. */
    STALE,
}

/** Where [record] stands in the V10 [store] and the 9.x [keys] ([Standing]). */
internal suspend fun ReservationPlan.standing(
    record: ImportedKey,
    store: ReservationStore,
    keys: NineXReservationSource,
    source: NineXEventSource,
): Standing {
    val held = store.find(record.scope(), record.key)
    if (held == null || !held.confirmed || held.owner != record.owner()) {
        return Standing.GONE
    }
    val current = withContext(Dispatchers.IO) { keys.key(record.name, record.key) }
    if (current == null || !current.confirmed) {
        return Standing.STALE
    }
    val owner = withContext(Dispatchers.IO) { ownerOf(current, source) }
    return if (owner == KeyOwner.Found(record.owner(), record.scope())) Standing.CURRENT else Standing.STALE
}

/**
 * The reservation comparison of `compare`, both ways (findings A4-8, R2.4-2): every confirmed 9.x key has a confirmed
 * V10 reservation of the same owner, and every key the import confirmed that V10 still holds for that owner is still
 * confirmed to it in 9.x. Counts the keys [compared], those that [differ] (missing, pending or another owner's in
 * V10), those [unresolved] (no single owner, so not imported), and the imported keys that are [stale] (released or
 * moved in 9.x).
 */
data class ReservationComparisonResult(
    val name: String,
    val compared: Long,
    val differ: Long,
    val unresolved: Long,
    val stale: Long = 0,
) {
    val passed: Boolean get() = differ == 0L && stale == 0L

    fun render(): String =
        "PrepareKey $name compared: $compared confirmed keys, $differ differ, $unresolved without an owner; " +
            "$stale imported keys no longer confirmed to their owner in 9.x"
}

/** Compares the 9.x `PrepareKey`s with the V10 reservations, every key on every run. */
class ReservationComparison(
    private val source: NineXEventSource,
    private val reservations: NineXReservationSource,
    private val plan: MigrationPlan,
    private val store: () -> ReservationStore,
    private val pageSize: Int,
    private val imported: ImportedKeys = InMemoryImportedKeys(),
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
        var stale = 0L
        val reservations = store()
        imported.forEach(name) { record ->
            if (reservationPlan.standing(record, reservations, this.reservations, source) == Standing.STALE) {
                report(
                    "DIFFERS PrepareKey $name/${record.key}: V10 ${record.owner()}, which 9.x no longer confirms it to"
                )
                stale++
            }
        }
        return result.copy(stale = stale).also { report(it.render()) }
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
