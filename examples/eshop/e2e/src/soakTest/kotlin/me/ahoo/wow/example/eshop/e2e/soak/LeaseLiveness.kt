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

/**
 * Which leases of a context `partitions.owned` holds to a live owner.
 *
 * A partition of a context's commands, its subscriptions or a topic's intake always needs one. The lease of a type's
 * access walk (`<group>#access-walk/<aggregate>`, one partition) does not: a node holds it only while it walks, and
 * when the walk completes releases it for good, leaving its row in `wow_lease`. It needs an owner while its walk is
 * due, and none after. A due walk no node has leased yet has no row at all and counts as a lease without an owner, so
 * a walk nobody picks up is caught with or without one.
 */
object LeaseLiveness {
    private const val WALK_GROUP = "#access-walk/"
    private const val WALK_PARTITION = 0

    /**
     * The leases that need a live owner, `group|partition` to the last owner (null when no node has held it) and the
     * expiry (epoch milliseconds, 0 released or never held).
     *
     * @param leases the rows of `wow_lease`, `group|partition` to the last owner and the expiry
     * @param walksDue the aggregate types whose access walk is recorded and not completed
     * @param walkGroup the lease group of an aggregate type's walk
     */
    fun requiringOwner(
        leases: Map<String, Pair<String, Long>>,
        walksDue: Set<String>,
        walkGroup: (String) -> String,
    ): Map<String, Pair<String?, Long>> {
        val leased: Map<String, Pair<String?, Long>> =
            leases.filterKeys { key -> walkOf(key)?.let { it in walksDue } ?: true }
        return walksDue.associate { "${walkGroup(it)}|$WALK_PARTITION" to (null to 0L) } + leased
    }

    /** The aggregate type of the walk lease [key] (`group|partition`), null for any other lease. */
    private fun walkOf(key: String): String? =
        key.substringBeforeLast('|').substringAfter(WALK_GROUP, "").ifEmpty { null }
}
