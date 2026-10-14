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

package me.ahoo.wow.api.aggregate

/**
 * Who an aggregate belongs to besides its tenant (decision D189), as `@AggregateRoot(ownership)` declares it.
 *
 * Every aggregate belongs to its tenant. Callers come at two levels: an owner-level caller (its scope has an owner)
 * reads and writes only its own aggregates; a tenant-level caller (no owner) reads and writes every aggregate of its
 * tenant, each action protected by action-level authorization (decisions D94, D129: a part absent from the caller's
 * scope is not checked).
 */
enum class Ownership {
    /**
     * The aggregate belongs to the owner of the command that creates it, when that command has one: an owner-level
     * caller reads and writes it only when it is theirs. The default, such as an order or a basket.
     */
    OWNER,

    /**
     * The aggregate belongs to its tenant alone, such as a product every shopper reads: its owner is never recorded,
     * a read is not restricted by the caller's owner, and a write is not checked against it. Its writes are protected
     * by action-level authorization (CoSec). A space still applies.
     */
    TENANT,
    ;

    /**
     * The part of [scope] this ownership keeps: all of it for [OWNER], its space alone for [TENANT]. It is what an
     * aggregate created in [scope] records, and what a caller in [scope] is checked against.
     */
    fun scopeOf(scope: Scope): Scope = when {
        this == TENANT && scope.ownerId != null -> scope.copy(ownerId = null)
        else -> scope
    }
}
