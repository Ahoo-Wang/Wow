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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BiOwnershipRegistryTest {
    @Test
    fun `registry entries should reject invalid ownership invariants`() {
        assertThrows<IllegalArgumentException> {
            entry(revision = 0)
        }.message.assert().contains("revision must be positive")
        assertThrows<IllegalArgumentException> {
            entry(aggregate = null)
        }.message.assert().contains("requires an aggregate owner")
        assertThrows<IllegalArgumentException> {
            entry(definitionFingerprint = "invalid")
        }.message.assert().contains("definitionFingerprint")
    }

    @Test
    fun `restored registry should reject invalid revision and key invariants`() {
        val entry = entry()

        assertThrows<IllegalArgumentException> {
            BiOwnershipRegistry.restore(DEPLOYMENT_ID, revision = -1, entries = emptyList())
        }.message.assert().contains("must not be negative")
        assertThrows<IllegalArgumentException> {
            BiOwnershipRegistry.restore(DEPLOYMENT_ID, revision = 1, entries = listOf(entry, entry))
        }.message.assert().contains("duplicate object keys")
        assertThrows<IllegalArgumentException> {
            BiOwnershipRegistry.restore(DEPLOYMENT_ID, revision = 0, entries = listOf(entry))
        }.message.assert().contains("entry revision is ahead")
    }

    @Test
    fun `registry should reject illegal mutation transitions`() {
        val pending = BiOwnershipRegistry.empty(DEPLOYMENT_ID).beginCreate(REGISTRATION)

        assertThrows<IllegalArgumentException> {
            pending.beginCreate(REGISTRATION)
        }.message.assert().contains("already registered")
        assertThrows<IllegalArgumentException> {
            pending.beginUpdate(REGISTRATION)
        }.message.assert().contains("update requires ACTIVE")
        assertThrows<IllegalArgumentException> {
            pending.beginDrop(KEY)
        }.message.assert().contains("drop requires ACTIVE or RETIRED")
        assertThrows<IllegalArgumentException> {
            pending.retire(KEY)
        }.message.assert().contains("transition requires ACTIVE")

        val active = pending.markMutationVerified(KEY)
        assertThrows<IllegalArgumentException> {
            active.markMutationVerified(KEY)
        }.message.assert().contains("mutation verification requires")
        assertThrows<IllegalArgumentException> {
            active.markAbsenceVerified(KEY)
        }.message.assert().contains("transition requires PENDING_DROP")
    }

    @Test
    fun `registry should reject mutations for an unknown object`() {
        val empty = BiOwnershipRegistry.empty(DEPLOYMENT_ID)

        assertThrows<IllegalArgumentException> {
            empty.beginUpdate(REGISTRATION)
        }.message.assert().contains("is not registered")
        assertThrows<IllegalArgumentException> {
            empty.beginDrop(KEY)
        }.message.assert().contains("is not registered")
    }

    private fun entry(
        aggregate: String? = "order",
        definitionFingerprint: String = DEFINITION_FINGERPRINT,
        revision: Long = 1,
    ): BiOwnershipRegistryEntry = BiOwnershipRegistryEntry(
        key = KEY,
        kind = BiObjectKind.VIEW,
        aggregate = aggregate,
        consumerIdentity = null,
        definitionFingerprint = definitionFingerprint,
        revision = revision,
        status = BiRegistryEntryStatus.ACTIVE,
    )

    private companion object {
        const val DEPLOYMENT_ID = "11111111111111111111111111111111"
        const val DEFINITION_FINGERPRINT = "22222222222222222222222222222222"
        val KEY = BiObjectKey("bi_db", "order_state")
        val REGISTRATION = BiOwnershipRegistration(
            key = KEY,
            kind = BiObjectKind.VIEW,
            aggregate = "order",
            consumerIdentity = null,
            definitionFingerprint = DEFINITION_FINGERPRINT,
        )
    }
}
