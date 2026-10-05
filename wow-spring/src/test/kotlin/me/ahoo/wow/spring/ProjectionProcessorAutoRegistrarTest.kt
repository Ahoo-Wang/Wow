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

package me.ahoo.wow.spring

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.ProjectionProcessor
import me.ahoo.wow.projection.ProjectionFunctionRegistrar
import me.ahoo.wow.spring.projection.ProjectionProcessorAutoRegistrar
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import java.util.function.Supplier

class ProjectionProcessorAutoRegistrarTest {

    @Test
    fun `a projection processor bean registers its functions once the context is ready`() {
        val functionRegistrar = ProjectionFunctionRegistrar()
        val applicationContext = GenericApplicationContext()
        applicationContext.registerBean("projection", FixtureProjection::class.java, Supplier(::FixtureProjection))
        applicationContext.registerBean(
            "projectionProcessorAutoRegistrar",
            ProjectionProcessorAutoRegistrar::class.java,
            Supplier { ProjectionProcessorAutoRegistrar(functionRegistrar, applicationContext) },
        )
        try {
            applicationContext.refresh()

            functionRegistrar.functions.single().processor.assert().isInstanceOf(FixtureProjection::class.java)
        } finally {
            applicationContext.close()
        }
    }

    @ProjectionProcessor
    class FixtureProjection {
        @OnEvent
        fun onEvent(@Suppress("UNUSED_PARAMETER", "UnusedParameter") event: FixtureEvent) = Unit
    }

    data class FixtureEvent(val id: String)
}
