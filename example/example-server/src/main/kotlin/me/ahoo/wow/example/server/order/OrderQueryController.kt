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

package me.ahoo.wow.example.server.order

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.exception.throwNotFoundIfEmpty
import me.ahoo.wow.query.SnapshotQueryService
import me.ahoo.wow.query.condition
import me.ahoo.wow.query.single
import me.ahoo.wow.query.toState
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@OpenAPIDefinition
@RestController
@RequestMapping("/order")
class OrderQueryController(
    @Qualifier("example.order.SnapshotQueryService")
    private val queryService: SnapshotQueryService<OrderState>
) {
    @GetMapping("{tenantId}/{orderId}")
    fun onQuery(@PathVariable tenantId: String, @PathVariable orderId: String): Mono<OrderState> {
        return condition {
            tenantId(tenantId)
            id(orderId)
        }.single(queryService).toState().throwNotFoundIfEmpty()
    }
}
