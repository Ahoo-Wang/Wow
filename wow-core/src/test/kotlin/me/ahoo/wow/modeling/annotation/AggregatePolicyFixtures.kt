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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.modeling.annotation

import me.ahoo.wow.api.annotation.AggregateOwner
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.annotation.Spaced
import me.ahoo.wow.api.annotation.StaticTenantId

// Spaced: neither / old only / new only / both equal / both different.
class NoPolicyAggregate(val id: String)

@AggregateRoute(resourceName = "routed")
class RouteWithoutPolicyAggregate(val id: String)

@AggregateRoute(spaced = true)
class LegacySpacedAggregate(val id: String)

@Spaced
class SpacedAggregate(val id: String)

@Spaced(false)
class NotSpacedAggregate(val id: String)

@Spaced
@AggregateRoute(spaced = true)
class BothSpacedAggregate(val id: String)

@Spaced
@AggregateRoute(resourceName = "routed")
class SpacedWithDefaultRouteAggregate(val id: String)

@Spaced(false)
@AggregateRoute(spaced = true)
class ConflictingSpacedAggregate(val id: String)

@Spaced
interface SpacedSupertype

class InheritedSpacedAggregate(val id: String) : SpacedSupertype

// Owner: old only / new only / both equal / both different.
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class LegacyOwnerAggregate(val id: String)

@AggregateOwner(OwnerPolicy.ALWAYS)
class OwnerAggregate(val id: String)

@AggregateOwner(OwnerPolicy.ALWAYS)
@AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
class BothOwnerAggregate(val id: String)

@AggregateOwner(OwnerPolicy.ALWAYS)
@AggregateRoute(resourceName = "routed")
class OwnerWithDefaultRouteAggregate(val id: String)

@AggregateOwner(OwnerPolicy.ALWAYS)
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class ConflictingOwnerAggregate(val id: String)

@AggregateOwner(OwnerPolicy.NEVER)
@AggregateRoute(owner = AggregateRoute.Owner.ALWAYS)
class ConflictingNeverOwnerAggregate(val id: String)

@StaticTenantId("tenant-a")
class StaticTenantAggregate(val id: String)

// Registered in the test wow-metadata.json, to prove the parser applies the policies and fails on a conflict.
@Spaced
@AggregateOwner(OwnerPolicy.AGGREGATE_ID)
class MockPolicyAggregate(val id: String)

@Spaced(false)
@AggregateRoute(spaced = true)
class MockConflictingSpacedAggregate(val id: String)

@AggregateOwner(OwnerPolicy.ALWAYS)
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class MockConflictingOwnerAggregate(val id: String)

/** Declared `tenant-b` in the test wow-metadata.json. */
@StaticTenantId("tenant-a")
class MockConflictingTenantAggregate(val id: String)

/** Declared `tenant-a` in the test wow-metadata.json. */
@StaticTenantId("tenant-a")
class MockAgreeingTenantAggregate(val id: String)
