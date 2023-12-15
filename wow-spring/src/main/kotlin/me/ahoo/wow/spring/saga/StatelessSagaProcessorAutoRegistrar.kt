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

package me.ahoo.wow.spring.saga

import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.saga.stateless.StatelessSagaFunctionRegistrar
import me.ahoo.wow.spring.AutoRegistrar
import org.springframework.context.ApplicationContext

class StatelessSagaProcessorAutoRegistrar(
    private val functionRegistrar: StatelessSagaFunctionRegistrar,
    private val commandGateway: CommandGateway,
    applicationContext: ApplicationContext
) : AutoRegistrar<StatelessSaga>(StatelessSaga::class.java, applicationContext) {
    override fun register(component: Any) {
        functionRegistrar.registerStatelessSaga(component, commandGateway)
    }
}
