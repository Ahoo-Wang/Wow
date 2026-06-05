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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotations
import org.junit.jupiter.api.Test

class AnnotationScanningBehaviorTest {

    @Test
    fun `should scan annotations merged from overridden function`() {
        val annotation = ScannedOperation::handle.scanAnnotation<ScanMarker>()

        annotation!!.value.assert().isEqualTo("base-function")
    }

    @Test
    fun `should return empty list when annotation is absent`() {
        ScannedOperation::class.scanAnnotations<ScanMarker>().assert().isEqualTo(emptyList<ScanMarker>())
    }
}

private interface ScannedOperationContract {
    @ScanMarker("base-function")
    fun handle(command: String)
}

private class ScannedOperation : ScannedOperationContract {
    override fun handle(command: String) = Unit
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
private annotation class ScanMarker(val value: String)
