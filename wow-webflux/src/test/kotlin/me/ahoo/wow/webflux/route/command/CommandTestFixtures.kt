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

package me.ahoo.wow.webflux.route.command

import me.ahoo.wow.command.factory.SimpleCommandBuilderRewriterRegistry
import me.ahoo.wow.command.factory.SimpleCommandMessageFactory
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.tck.mock.MockCreateAggregate
import me.ahoo.wow.webflux.route.command.extractor.CommandMessageExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandBuilderExtractor
import me.ahoo.wow.webflux.route.command.extractor.DefaultCommandMessageExtractor
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import reactor.kotlin.core.publisher.toMono

internal object CommandTestFixtures {

    val MOCK_COMMAND_MESSAGE_FACTORY = SimpleCommandMessageFactory(
        NoOpValidator,
        SimpleCommandBuilderRewriterRegistry()
    )

    val MOCK_COMMAND_MESSAGE_EXTRACTOR: CommandMessageExtractor = DefaultCommandMessageExtractor(
        MOCK_COMMAND_MESSAGE_FACTORY,
        DefaultCommandBuilderExtractor
    )

    fun mockCommandRequest(
        commandBody: Any = MockCreateAggregate(id = generateGlobalId(), data = generateGlobalId()),
        block: MockServerRequest.Builder.() -> Unit = {}
    ): MockServerRequest {
        return MockServerRequest.builder()
            .method(org.springframework.http.HttpMethod.POST)
            .header(CommandComponent.Header.WAIT_STAGE, me.ahoo.wow.command.wait.CommandStage.SENT.name)
            .header(CommandComponent.Header.TENANT_ID, generateGlobalId())
            .header(CommandComponent.Header.OWNER_ID, generateGlobalId())
            .header(CommandComponent.Header.AGGREGATE_ID, generateGlobalId())
            .header(CommandComponent.Header.REQUEST_ID, generateGlobalId())
            .body(commandBody.toMono())
            .apply(block)
    }
}
