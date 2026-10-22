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

package me.ahoo.wow.spi.reservation

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import kotlin.time.Duration

/**
 * Reserved keys: uniqueness across aggregate instances, such as a user name unique within a tenant
 * (documentation/designs/01-programming-model.md §7, decisions D38, D50; 9.x `PrepareKey`).
 *
 * A key is unique within its [ReservationScope]: one bounded context, one tenant (or [ReservationScope.SYSTEM_TENANT]
 * for keys unique across the context's tenants) and one namespace. It is held by at most one owner, an aggregate
 * ([AggregateRef], compared field by field). A decision reserves it **pending** with a short time to live, and confirms
 * it once its events are appended (finding R8): a process that crashes between reserving and appending leaves only a
 * pending reservation that expires, never a key held for good. A confirmed reservation never expires; it ends only
 * when its owner releases it.
 *
 * Several attempts of one owner may reserve one key at once (a handover, a forward that timed out): a pending
 * reservation records each attempt that reserved it, its **holds**, and an attempt whose events are surely not
 * appended [abandon]s only its own hold. The key stays reserved while another attempt still holds it, and a confirmed
 * key is never abandoned (finding A1-2).
 *
 * Every operation that changes a reservation names its owner and does nothing for anyone else. Expiry is judged by
 * the implementation's clock, so the nodes sharing a store need clocks that agree within a small fraction of the
 * time to live.
 */
@WowSpi
interface ReservationStore {
    /**
     * Reserves [key] in [scope] for [owner]'s attempt [hold], pending for [ttl]. A key held by no one, or only by an
     * expired pending reservation (whoever's), is taken: pending, its only hold [hold]. When [owner] holds the key
     * pending and live, [hold] joins its holds and the later expiry is kept; when [owner] holds it confirmed, it stays
     * as it is.
     *
     * @return the reservation [owner] now holds, or null when another owner's live reservation holds the key.
     */
    suspend fun reserve(
        scope: ReservationScope,
        key: String,
        owner: AggregateRef,
        hold: String,
        ttl: Duration,
    ): Reservation?

    /**
     * Abandons [owner]'s attempt [hold] on [key]: an attempt whose events are surely not appended. Removes [hold] from
     * a pending reservation of [owner] that has it, and frees the key once no hold is left. A confirmed reservation,
     * another owner's or one without [hold] is left as it is.
     *
     * @return true when [hold] was removed.
     */
    suspend fun abandon(scope: ReservationScope, key: String, owner: AggregateRef, hold: String): Boolean

    /**
     * Confirms [owner]'s reservation of [key] for a committed fact, making it permanent and clearing its holds. A
     * pending reservation of [owner] is confirmed even if it expired; a key held by no one, or only by another owner's
     * expired pending reservation, is taken confirmed: the fact committed, so it gets its key back. Confirming again
     * changes nothing, so a replay is idempotent.
     *
     * @return [ConfirmOutcome.Confirmed], or [ConfirmOutcome.Taken] when another owner's live reservation holds the key.
     */
    suspend fun confirm(scope: ReservationScope, key: String, owner: AggregateRef): ConfirmOutcome

    /**
     * Releases [owner]'s reservation of [key], pending or confirmed, whatever its holds: the owner gave the key up in
     * a committed fact (a rename), or an operator frees it.
     *
     * @return true when [owner] held the key and released it.
     */
    suspend fun release(scope: ReservationScope, key: String, owner: AggregateRef): Boolean

    /** The live reservation of [key] in [scope]: confirmed, or pending and not expired. */
    suspend fun find(scope: ReservationScope, key: String): Reservation?
}

/**
 * Where a reserved key is unique: the bounded [context] of its owners, the [tenantId] (axiom A6: every unique key
 * includes the tenant) and the [namespace], such as `user.name`. A key unique across the context's tenants has the
 * tenant [SYSTEM_TENANT], which no aggregate's tenant ever is.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class ReservationScope @InternalWowApi constructor(val context: String, val tenantId: String, val namespace: String) {
    init {
        require(context.isNotBlank()) { "A reservation needs a context." }
        require(tenantId == SYSTEM_TENANT || tenantId.isNotBlank()) { "A reservation's tenant is not blank." }
        require(namespace.isNotBlank()) { "A reservation needs a namespace." }
    }

    /** Whether the keys of this scope are unique across tenants. */
    val isSystem: Boolean get() = tenantId == SYSTEM_TENANT

    /** This ReservationScope with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        context: String = this.context,
        tenantId: String = this.tenantId,
        namespace: String = this.namespace,
    ): ReservationScope = ReservationScope(context, tenantId, namespace)

    override fun equals(other: Any?): Boolean =
        this === other || other is ReservationScope && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ReservationScope(context=$context, tenantId=$tenantId, namespace=$namespace)"

    private fun valueFields(): List<Any?> = listOf(context, tenantId, namespace)

    companion object {
        /**
         * The tenant of keys unique across the tenants of a context: empty, which no aggregate's tenant is (an
         * [AggregateRef]'s tenant is never blank), so it never meets a tenant's own keys.
         */
        const val SYSTEM_TENANT: String = ""

        /** The scope of [key] as [owner] reserves it: its context, and its tenant unless the key is a system one. */
        @JvmStatic
        fun of(owner: AggregateRef, key: ReservationKey): ReservationScope = ReservationScope(
            owner.type.context,
            if (key.system) SYSTEM_TENANT else owner.tenantId,
            key.namespace,
        )

        /** A ReservationScope of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        fun of(context: String, tenantId: String, namespace: String): ReservationScope =
            ReservationScope(context, tenantId, namespace)
    }
}

/**
 * [owner]'s reservation of [key] in [scope]. A pending reservation expires at [expiresAt] (epoch milliseconds) and is
 * held by the attempts in [holds]; a confirmed one has no expiry and no holds. Its owner is an aggregate of the
 * scope's context.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class Reservation @InternalWowApi constructor(
    val scope: ReservationScope,
    val key: String,
    val owner: AggregateRef,
    val expiresAt: Long?,
    val holds: Set<String> = emptySet(),
) {
    init {
        require(key.isNotEmpty()) { "A reservation needs a key." }
        require(owner.type.context == scope.context) {
            "A key of ${scope.context} is reserved by an aggregate of that context, not $owner."
        }
        require(expiresAt != null || holds.isEmpty()) { "A confirmed reservation has no holds." }
    }

    /** Whether the reservation is permanent. */
    val confirmed: Boolean get() = expiresAt == null

    /** Whether the reservation still holds its key at [now] (epoch milliseconds). */
    fun isLive(now: Long): Boolean = expiresAt == null || now < expiresAt

    /** This Reservation with the given fields replaced: Wow's own copy, left out of the ABI. */
    @InternalWowApi
    fun copyWith(
        scope: ReservationScope = this.scope,
        key: String = this.key,
        owner: AggregateRef = this.owner,
        expiresAt: Long? = this.expiresAt,
        holds: Set<String> = this.holds,
    ): Reservation = Reservation(scope, key, owner, expiresAt, holds)

    override fun equals(other: Any?): Boolean =
        this === other || other is Reservation && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "Reservation(scope=$scope, key=$key, owner=$owner, expiresAt=$expiresAt, holds=$holds)"

    private fun valueFields(): List<Any?> = listOf(scope, key, owner, expiresAt, holds)

    companion object {
        /** A Reservation of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(
            scope: ReservationScope,
            key: String,
            owner: AggregateRef,
            expiresAt: Long?,
            holds: Set<String> = emptySet(),
        ): Reservation = Reservation(scope, key, owner, expiresAt, holds)
    }
}

/** How a [ReservationStore.confirm] ended. */
sealed interface ConfirmOutcome {
    /** The owner holds the key confirmed. */
    data object Confirmed : ConfirmOutcome

    /**
     * Another owner's live reservation, [by], holds the key: the fact committed without it.
     *
     * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
     */
    class Taken @InternalWowApi constructor(val by: Reservation) : ConfirmOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || other is Taken && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Taken(by=$by)"

        private fun valueFields(): List<Any?> = listOf(by)

        companion object {
            /** A Taken of the given fields; parameters added later come last, with a default. */
            @JvmStatic
            fun of(by: Reservation): Taken = Taken(by)
        }
    }
}

/**
 * A reserved or released [key] in [namespace], without its owner or expiry: what an append records (decision D50).
 * Its scope follows from the entry's aggregate ([ReservationScope.of]): its context and its tenant, or no tenant when
 * the key is a [system] one, unique across tenants.
 *
 * Fields may be added (decisions D59, D228): construct one with [of]; the constructor is internal to Wow.
 */
class ReservationKey @InternalWowApi constructor(val namespace: String, val key: String, val system: Boolean = false) {
    init {
        require(namespace.isNotBlank()) { "A reservation needs a namespace." }
        require(key.isNotEmpty()) { "A reservation needs a key." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ReservationKey && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "ReservationKey(namespace=$namespace, key=$key, system=$system)"

    private fun valueFields(): List<Any?> = listOf(namespace, key, system)

    companion object {
        /** A ReservationKey of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(namespace: String, key: String, system: Boolean = false): ReservationKey =
            ReservationKey(namespace, key, system)
    }
}
