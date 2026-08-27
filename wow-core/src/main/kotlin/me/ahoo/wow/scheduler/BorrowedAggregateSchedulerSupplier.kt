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

package me.ahoo.wow.scheduler

import me.ahoo.wow.api.modeling.NamedAggregate
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

/**
 * A non-owning lifecycle view over an aggregate scheduler supplier.
 *
 * Composite components pass this view to children so every child can execute
 * its complete lifecycle without closing a resource owned by the composite.
 */
internal class BorrowedAggregateSchedulerSupplier(
    private val delegate: AggregateSchedulerSupplier,
) : AggregateSchedulerSupplier {
    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        delegate.getOrInitialize(namedAggregate)

    override fun stopGracefully(): Mono<Void> = Mono.empty()

    override fun forceStop() = Unit
}
