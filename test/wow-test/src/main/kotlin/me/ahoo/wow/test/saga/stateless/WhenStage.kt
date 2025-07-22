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

package me.ahoo.wow.test.saga.stateless

import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.naming.annotation.toName
import kotlin.reflect.KType
import kotlin.reflect.full.defaultType

/**
 * Stateless Saga:
 * 1. when event
 * 2. expect commands
 */
interface WhenStage<T : Any> {
    fun <SERVICE : Any> inject(
        service: SERVICE,
        serviceName: String = service.javaClass.toName(),
        serviceType: KType = service.javaClass.kotlin.defaultType
    ): WhenStage<T> {
        inject {
            register(service, serviceName, serviceType)
        }
        return this
    }

    fun inject(inject: ServiceProvider.() -> Unit): WhenStage<T>
    fun functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean): WhenStage<T>

    fun functionName(functionName: String): WhenStage<T> {
        return functionFilter {
            it.name == functionName
        }
    }

    /**
     * 1. 当订阅到领域事件时，生成聚合命令.
     */
    fun `when`(event: Any, state: Any?, ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID): ExpectStage<T> {
        return whenEvent(event = event, state = state, ownerId = ownerId)
    }

    fun `when`(event: Any): ExpectStage<T> {
        return whenEvent(event = event, state = null)
    }

    fun whenEvent(event: Any, state: Any? = null, ownerId: String = OwnerId.Companion.DEFAULT_OWNER_ID): ExpectStage<T>
}
