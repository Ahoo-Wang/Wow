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

package me.ahoo.wow.modeling.annotation

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.modeling.annotation.AggregatePolicyResolver.resolveOwnerPolicy
import me.ahoo.wow.modeling.annotation.AggregatePolicyResolver.resolveSpaced
import me.ahoo.wow.modeling.annotation.AggregatePolicyResolver.resolveStaticTenantId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.reflect.KClass

class AggregatePolicyResolverTest {
    companion object {
        @JvmStatic
        fun spacedMatrix(): List<Arguments> = listOf(
            Arguments.of(NoPolicyAggregate::class, false),
            Arguments.of(RouteWithoutPolicyAggregate::class, false),
            Arguments.of(LegacySpacedAggregate::class, true),
            Arguments.of(SpacedAggregate::class, true),
            Arguments.of(NotSpacedAggregate::class, false),
            Arguments.of(BothSpacedAggregate::class, true),
            Arguments.of(SpacedWithDefaultRouteAggregate::class, true),
            Arguments.of(InheritedSpacedAggregate::class, true),
        )

        @JvmStatic
        fun ownerMatrix(): List<Arguments> = listOf(
            Arguments.of(NoPolicyAggregate::class, OwnerPolicy.NEVER),
            Arguments.of(RouteWithoutPolicyAggregate::class, OwnerPolicy.NEVER),
            Arguments.of(LegacyOwnerAggregate::class, OwnerPolicy.AGGREGATE_ID),
            Arguments.of(OwnerAggregate::class, OwnerPolicy.ALWAYS),
            Arguments.of(BothOwnerAggregate::class, OwnerPolicy.ALWAYS),
            Arguments.of(OwnerWithDefaultRouteAggregate::class, OwnerPolicy.ALWAYS),
        )
    }

    @ParameterizedTest
    @MethodSource("spacedMatrix")
    fun `spaced is read from @Spaced, then from AggregateRoute, then defaults`(type: KClass<*>, spaced: Boolean) {
        type.resolveSpaced().assert().isEqualTo(spaced)
    }

    @Test
    fun `spaced declared on both with different values fails`() {
        assertThrows<IllegalStateException> {
            ConflictingSpacedAggregate::class.resolveSpaced()
        }.message.assert().contains("@Spaced(false)", "@AggregateRoute(spaced = true)")
    }

    @ParameterizedTest
    @MethodSource("ownerMatrix")
    fun `owner is read from @AggregateOwner, then from AggregateRoute, then defaults`(
        type: KClass<*>,
        owner: OwnerPolicy
    ) {
        type.resolveOwnerPolicy().assert().isEqualTo(owner)
    }

    @Test
    fun `owner declared on both with different policies fails`() {
        assertThrows<IllegalStateException> {
            ConflictingOwnerAggregate::class.resolveOwnerPolicy()
        }.message.assert().contains("OwnerPolicy.ALWAYS", "Owner.AGGREGATE_ID")
        assertThrows<IllegalStateException> {
            ConflictingNeverOwnerAggregate::class.resolveOwnerPolicy()
        }
    }

    @Test
    fun `static tenant is read from the annotation, then from the metadata resource`() {
        NoPolicyAggregate::class.resolveStaticTenantId(null).assert().isNull()
        NoPolicyAggregate::class.resolveStaticTenantId("tenant-b").assert().isEqualTo("tenant-b")
        StaticTenantAggregate::class.resolveStaticTenantId(null).assert().isEqualTo("tenant-a")
        StaticTenantAggregate::class.resolveStaticTenantId(" ").assert().isEqualTo("tenant-a")
        StaticTenantAggregate::class.resolveStaticTenantId("tenant-a").assert().isEqualTo("tenant-a")
    }

    @Test
    fun `static tenant declared in both places with different values fails`() {
        assertThrows<IllegalStateException> {
            StaticTenantAggregate::class.resolveStaticTenantId("tenant-b")
        }.message.assert().contains("tenant-a", "tenant-b")
    }

    @Test
    fun `parser applies the declared policies`() {
        val metadata = aggregateMetadata<MockPolicyAggregate, MockPolicyAggregate>()
        metadata.spaced.assert().isTrue()
        metadata.owner.assert().isEqualTo(OwnerPolicy.AGGREGATE_ID)
        aggregateMetadata<MockAggregate, MockAggregate>().owner.assert().isEqualTo(OwnerPolicy.NEVER)
        aggregateMetadata<MockAgreeingTenantAggregate, MockAgreeingTenantAggregate>()
            .staticTenantId.assert().isEqualTo("tenant-a")
    }

    @Test
    fun `parser fails on conflicting declarations`() {
        assertThrows<IllegalStateException> {
            aggregateMetadata<MockConflictingSpacedAggregate, MockConflictingSpacedAggregate>()
        }
        assertThrows<IllegalStateException> {
            aggregateMetadata<MockConflictingOwnerAggregate, MockConflictingOwnerAggregate>()
        }
        assertThrows<IllegalStateException> {
            aggregateMetadata<MockConflictingTenantAggregate, MockConflictingTenantAggregate>()
        }
    }
}
