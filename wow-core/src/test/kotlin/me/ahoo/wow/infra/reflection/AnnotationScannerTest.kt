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

package me.ahoo.wow.infra.reflection

import me.ahoo.wow.api.annotation.ProjectionProcessor
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test

internal class AnnotationScannerTest {

    @Test
    fun scanProjector() {
        val annotation = Projector::class.java.scan<ProjectionProcessor>()
        assertThat(annotation, notNullValue())
    }

    @Test
    fun scanIntegrated() {
        val annotation = IntegratedAnnotation::class.java.scan<ProjectionProcessor>()
        assertThat(annotation, notNullValue())
    }
}

@ProjectionProcessor
open class Projector

class IntegratedAnnotation : Projector()
