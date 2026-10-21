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

package me.ahoo.wow.models.tree.aggregate

/**
 * The codes a [Tree] rejects a command with. 9.x threw `IllegalStateException` or `IllegalArgumentException`; in 10.x
 * a rejection is a value with a stable code. Each rejection's extensions carry the codes or levels at fault.
 *
 * The compiler lists only the `@ErrorCode` constants of the module it processes in the Manifest, so these are not in
 * a domain's Manifest by themselves: an HTTP edge answers them 422, unless the domain declares them (a `@ErrorCode`
 * constant of the same literal) or the host maps their status.
 */
object TreeRejections {
    /** The parent of the node to create does not exist. */
    const val PARENT_NOT_FOUND = "tree.parent-not-found"

    /** The node to create would be deeper than the tree's maximum level. */
    const val MAX_LEVEL_EXCEEDED = "tree.max-level-exceeded"

    /** The node to update or delete does not exist. */
    const val NODE_NOT_FOUND = "tree.node-not-found"

    /** The node to delete has children. */
    const val NODE_HAS_CHILDREN = "tree.node-has-children"

    /** The nodes to move are not existing siblings, each given once. */
    const val INVALID_MOVE = "tree.invalid-move"
}
