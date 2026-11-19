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

package me.ahoo.wow.spi.log

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import org.junit.jupiter.api.Test

class CreationClaimTest {
    @Test
    fun `a claim is the value of its aggregate id and scope`() {
        val claim = CreationClaim.of("o-1", Scope("alice", "home"))
        claim.assert().isEqualTo(claim)
        claim.assert().isEqualTo(CreationClaim.of("o-1", Scope("alice", "home")))
        claim.hashCode().assert().isEqualTo(CreationClaim.of("o-1", Scope("alice", "home")).hashCode())
        claim.assert().isNotEqualTo(CreationClaim.of("o-2", Scope("alice", "home")))
        claim.assert().isNotEqualTo(CreationClaim.of("o-1", Scope("bob")))
        claim.assert().isNotEqualTo("o-1")
        claim.toString().assert().isEqualTo("CreationClaim(aggregateId=o-1, scope=${Scope("alice", "home")})")
    }
}
