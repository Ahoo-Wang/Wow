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

package me.ahoo.wow.cosec.extractor

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor.REQUEST_ID_KEY
import me.ahoo.wow.cosec.extractor.CoSecCommandBuilderExtractor.SPACE_ID_KEY
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.aggregateRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockCreateAggregate
import org.junit.jupiter.api.Test
import org.springframework.mock.web.reactive.function.server.MockServerRequest
import org.springframework.web.reactive.function.server.ServerRequest

class CoSecCommandBuilderExtractorTest {
    private val routeMetadata = MOCK_AGGREGATE_METADATA.command.aggregateType.aggregateRouteMetadata()

    private fun request(): ServerRequest = MockServerRequest.builder()
        .pathVariable(MessageRecords.TENANT_ID, generateGlobalId())
        .pathVariable(MessageRecords.OWNER_ID, generateGlobalId())
        .pathVariable(CommandComponent.Header.AGGREGATE_VERSION, 1.toString())
        .header(CommandComponent.Header.WAIT_STAGE, CommandStage.SENT.toString())
        .header(CommandComponent.Header.LOCAL_FIRST, false.toString())
        .header(REQUEST_ID_KEY, generateGlobalId())
        .header(SPACE_ID_KEY, generateGlobalId())
        .build()

    private fun extract(aggregateRouteMetadata: AggregateRouteMetadata<*>, request: ServerRequest) =
        CoSecCommandBuilderExtractor.extract(
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandBody = MockCreateAggregate(
                id = generateGlobalId(),
                data = generateGlobalId(),
            ),
            request
        ).block()!!

    @Test
    fun `should extract request id and space id from CoSec headers for a spaced aggregate`() {
        val request = request()
        val commandBuilder = extract(routeMetadata.copy(spaced = true), request)
        commandBuilder.requestId.assert()
            .isEqualTo(request.headers().firstHeader(REQUEST_ID_KEY))
        commandBuilder.spaceId.assert()
            .isEqualTo(request.headers().firstHeader(SPACE_ID_KEY))
    }

    @Test
    fun `should ignore the CoSec space header for a non-spaced aggregate`() {
        routeMetadata.spaced.assert().isFalse()
        val request = request()
        val commandBuilder = extract(routeMetadata, request)
        commandBuilder.requestId.assert()
            .isEqualTo(request.headers().firstHeader(REQUEST_ID_KEY))
        commandBuilder.spaceId.assert().isNull()
    }
}
