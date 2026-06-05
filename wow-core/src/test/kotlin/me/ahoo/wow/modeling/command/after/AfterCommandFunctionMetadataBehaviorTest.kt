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

package me.ahoo.wow.modeling.command.after

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.messaging.function.FunctionMetadataParser.toMonoFunctionMetadata
import me.ahoo.wow.modeling.annotation.CreateCmd
import me.ahoo.wow.modeling.annotation.MockAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.MockDefaultAfterCommandAggregate
import me.ahoo.wow.modeling.annotation.UpdateCmd
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunction
import me.ahoo.wow.modeling.command.after.AfterCommandFunctionMetadata.Companion.toAfterCommandFunctionMetadata
import org.junit.jupiter.api.Test

class AfterCommandFunctionMetadataBehaviorTest {

    @Test
    fun `metadata reads include exclude and order annotations`() {
        val metadata = MockAfterCommandAggregate::onAfterCommand
            .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()

        metadata.include.assert().containsExactly(CreateCmd::class.java)
        metadata.exclude.assert().containsExactly(UpdateCmd::class.java)
        metadata.order.value.assert().isEqualTo(0)
        metadata.function.functionKind.assert().isEqualTo(FunctionKind.COMMAND)
    }

    @Test
    fun `support command honors exclude before include and supports all when include is empty`() {
        val filtered = MockAfterCommandAggregate::onAfterCommand
            .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()
        val default = MockDefaultAfterCommandAggregate::afterCommand
            .toMonoFunctionMetadata<MockDefaultAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()

        filtered.supportCommand(CreateCmd::class.java).assert().isTrue()
        filtered.supportCommand(UpdateCmd::class.java).assert().isFalse()
        default.supportCommand(UpdateCmd::class.java).assert().isTrue()
    }

    @Test
    fun `metadata reads order annotation and creates executable after command function`() {
        val metadata = MockAfterCommandAggregate::firstAfterCommand
            .toMonoFunctionMetadata<MockAfterCommandAggregate, Any>()
            .toAfterCommandFunctionMetadata()
        val commandRoot = MockAfterCommandAggregate("aggregate-1")

        val function = metadata.toAfterCommandFunction(commandRoot)

        metadata.order.value.assert().isEqualTo(ORDER_FIRST)
        function.metadata.assert().isSameAs(metadata)
        function.processor.assert().isSameAs(commandRoot)
        function.order.value.assert().isEqualTo(ORDER_FIRST)
    }
}
