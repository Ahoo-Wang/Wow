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
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredMemberProperties

class BiPublicApiTest {
    @Test
    fun `should expose only the supported Kotlin API types`() {
        val publicTypes = productionTypes()
            .filter { it.isEffectivelyPublic() }
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

    @Test
    fun `should keep option defaults private`() {
        val companion = requireNotNull(BiScriptOptions::class.companionObject)

        companion.visibility.assert().isEqualTo(KVisibility.PRIVATE)
        companion.declaredMemberProperties
            .associate { it.name to it.visibility }
            .assert()
            .isEqualTo(
                mapOf(
                    "DEFAULT_KAFKA_BOOTSTRAP_SERVERS" to KVisibility.PRIVATE,
                    "DEFAULT_TOPIC_PREFIX" to KVisibility.PRIVATE,
                )
            )
        listOf("DEFAULT_KAFKA_BOOTSTRAP_SERVERS", "DEFAULT_TOPIC_PREFIX")
            .all { fieldName ->
                Modifier.isPrivate(BiScriptOptions::class.java.getDeclaredField(fieldName).modifiers)
            }
            .assert()
            .isTrue()
    }

    private fun productionTypes(): List<KClass<*>> {
        val classesRoot = Path.of(
            BiScriptGenerator::class.java.protectionDomain.codeSource.location.toURI()
        )
        val packageRoot = classesRoot.resolve(PACKAGE_NAME.replace('.', '/'))
        return Files.walk(packageRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.name.endsWith(".class") }
                .map { classFile ->
                    classesRoot.relativize(classFile)
                        .toString()
                        .removeSuffix(".class")
                        .replace('/', '.')
                }
                .map { Class.forName(it, false, javaClass.classLoader) }
                .filter { !it.isLocalClass && !it.isAnonymousClass && !it.isSynthetic }
                .filter { it.isKotlinClassDeclaration }
                .map { it.kotlin }
                .toList()
        }
    }

    private fun KClass<*>.isEffectivelyPublic(): Boolean =
        generateSequence(java) { it.enclosingClass }
            .filter { it.isKotlinClassDeclaration }
            .map { it.kotlin }
            .all { it.visibility == KVisibility.PUBLIC }

    private val Class<*>.isKotlinClassDeclaration: Boolean
        get() = getAnnotation(Metadata::class.java)?.kind == CLASS_KIND

    private companion object {
        const val PACKAGE_NAME = "me.ahoo.wow.bi"
        const val CLASS_KIND = 1
    }
}
