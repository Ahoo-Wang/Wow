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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.modeling.NamedAggregateDecorator
import tools.jackson.databind.node.ObjectNode

/**
 * Masks an [ObjectNode] owned exclusively by the current query subscription.
 *
 * A masker may mutate and return the input node or return a replacement node. It must not cache or share the node
 * across subscriptions, publish it asynchronously, or mutate it after this call returns. The returned node must remain
 * a standard JSON tree and preserve the required Snapshot/EventStream envelope fields and JSON field types needed for
 * typed materialization. A violation makes the typed query fail closed; the Gateway does not restore fields or bypass
 * masking.
 */
fun interface ObjectNodeMasker {
    fun mask(node: ObjectNode): ObjectNode
}

interface AggregateObjectNodeMasker : ObjectNodeMasker, NamedAggregateDecorator

interface StateObjectNodeMasker : AggregateObjectNodeMasker

interface EventStreamObjectNodeMasker : AggregateObjectNodeMasker
