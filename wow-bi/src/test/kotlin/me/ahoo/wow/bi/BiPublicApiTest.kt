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

package me.ahoo.wow.bi

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility

class BiPublicApiTest {
    @Test
    fun `should expose only the supported Kotlin API types`() {
        val publicTypes = productionTypes()
            .filter { it.visibility == KVisibility.PUBLIC }
            .mapNotNull(KClass<*>::qualifiedName)
            .sorted()

        publicTypes.assert().containsExactly(
            BiScriptDiagnostic::class.qualifiedName,
            BiScriptDiagnosticCode::class.qualifiedName,
            BiScriptGenerator::class.qualifiedName,
            BiScriptMappingDecision::class.qualifiedName,
            BiScriptOptions::class.qualifiedName,
            BiScriptResult::class.qualifiedName,
            UnsupportedTypeStrategy::class.qualifiedName,
        )
    }

    private fun productionTypes(): List<KClass<*>> {
        val classesRoot = Path.of(
            BiScriptGenerator::class.java.protectionDomain.codeSource.location.toURI()
        )
        val packageRoot = classesRoot.resolve(PACKAGE_NAME.replace('.', '/'))
        return Files.walk(packageRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith(".class") && '$' !in it.name }
                .map { classFile ->
                    classesRoot.relativize(classFile)
                        .toString()
                        .removeSuffix(".class")
                        .replace('/', '.')
                }
                .map { Class.forName(it, false, javaClass.classLoader).kotlin }
                .filter { it.java.getAnnotation(Metadata::class.java)?.kind == CLASS_KIND }
                .toList()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "me.ahoo.wow.bi"
        const val CLASS_KIND = 1
    }
}
