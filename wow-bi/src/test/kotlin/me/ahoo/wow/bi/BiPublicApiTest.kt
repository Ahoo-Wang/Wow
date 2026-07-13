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
            BiConsumerIdentity::class.qualifiedName,
            BiConsumerIdentity.Companion::class.qualifiedName,
            BiDeploymentDescriptor::class.qualifiedName,
            BiDeploymentDescriptor.Companion::class.qualifiedName,
            BiDeploymentInspection::class.qualifiedName,
            BiDeploymentInspection.Available::class.qualifiedName,
            BiDeploymentInspection.Unavailable::class.qualifiedName,
            BiDeploymentInspectionException::class.qualifiedName,
            BiDeploymentInspectionException.Companion::class.qualifiedName,
            BiDeploymentInspectionException.Inconsistent::class.qualifiedName,
            BiDeploymentInspectionException.Timeout::class.qualifiedName,
            BiDeploymentInspectionException.Unavailable::class.qualifiedName,
            BiDeploymentInspector::class.qualifiedName,
            BiObjectKey::class.qualifiedName,
            BiObjectKind::class.qualifiedName,
            BiObjectMetadata::class.qualifiedName,
            BiObjectMetadata.Companion::class.qualifiedName,
            BiObjectMetadataCodec::class.qualifiedName,
            BiScriptDiagnostic::class.qualifiedName,
            BiScriptDiagnosticCode::class.qualifiedName,
            BiScriptGenerator::class.qualifiedName,
            BiScriptMappingDecision::class.qualifiedName,
            BiScriptOperation::class.qualifiedName,
            BiScriptOperation.Deploy::class.qualifiedName,
            BiScriptOperation.Reset::class.qualifiedName,
            BiScriptOptions::class.qualifiedName,
            BiScriptOptions.Companion::class.qualifiedName,
            BiScriptResult::class.qualifiedName,
            ClickHouseBiDeploymentInspector::class.qualifiedName,
            ClickHouseClientOptions::class.qualifiedName,
            ClickHouseTopology::class.qualifiedName,
            ClickHouseTopology.Cluster::class.qualifiedName,
            ClickHouseTopology.Cluster.Companion::class.qualifiedName,
            ClickHouseTopology.Standalone::class.qualifiedName,
            KafkaOffsetStorage::class.qualifiedName,
            NoOpBiDeploymentInspector::class.qualifiedName,
            ObservedBiDeployment::class.qualifiedName,
            ObservedBiObject::class.qualifiedName,
            UnsupportedTypeStrategy::class.qualifiedName,
        )
    }

    @Test
    fun `should expose option limits and keep defaults private`() {
        val companion = requireNotNull(BiScriptOptions::class.companionObject)

        companion.visibility.assert().isEqualTo(KVisibility.PUBLIC)
        companion.declaredMemberProperties
            .associate { it.name to it.visibility }
            .assert()
            .isEqualTo(
                mapOf(
                    "DEFAULT_KAFKA_BOOTSTRAP_SERVERS" to KVisibility.PRIVATE,
                    "DEFAULT_KAFKA_KEEPER_PATH_PREFIX" to KVisibility.PRIVATE,
                    "DEFAULT_TOPIC_PREFIX" to KVisibility.PRIVATE,
                    "MAX_CONSUMER_DATABASE_LENGTH" to KVisibility.PUBLIC,
                    "MAX_CONSUMER_GROUP_NAMESPACE_LENGTH" to KVisibility.PUBLIC,
                    "MAX_DATABASE_LENGTH" to KVisibility.PUBLIC,
                    "MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH" to KVisibility.PUBLIC,
                    "MAX_KAFKA_KEEPER_PATH_PREFIX_LENGTH" to KVisibility.PUBLIC,
                    "MAX_TIMEZONE_LENGTH" to KVisibility.PUBLIC,
                    "MAX_TOPIC_PREFIX_LENGTH" to KVisibility.PUBLIC,
                )
            )
        listOf("DEFAULT_KAFKA_BOOTSTRAP_SERVERS", "DEFAULT_TOPIC_PREFIX")
            .all { fieldName ->
                Modifier.isPrivate(BiScriptOptions::class.java.getDeclaredField(fieldName).modifiers)
            }
            .assert()
            .isTrue()
        mapOf(
            "MAX_DATABASE_LENGTH" to BiScriptOptions.MAX_DATABASE_LENGTH,
            "MAX_CONSUMER_DATABASE_LENGTH" to BiScriptOptions.MAX_CONSUMER_DATABASE_LENGTH,
            "MAX_TIMEZONE_LENGTH" to BiScriptOptions.MAX_TIMEZONE_LENGTH,
            "MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH" to BiScriptOptions.MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH,
            "MAX_TOPIC_PREFIX_LENGTH" to BiScriptOptions.MAX_TOPIC_PREFIX_LENGTH,
            "MAX_CONSUMER_GROUP_NAMESPACE_LENGTH" to BiScriptOptions.MAX_CONSUMER_GROUP_NAMESPACE_LENGTH,
            "MAX_KAFKA_KEEPER_PATH_PREFIX_LENGTH" to BiScriptOptions.MAX_KAFKA_KEEPER_PATH_PREFIX_LENGTH,
        ).assert().isEqualTo(
            mapOf(
                "MAX_DATABASE_LENGTH" to 128,
                "MAX_CONSUMER_DATABASE_LENGTH" to 128,
                "MAX_TIMEZONE_LENGTH" to 64,
                "MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH" to 4096,
                "MAX_TOPIC_PREFIX_LENGTH" to 128,
                "MAX_CONSUMER_GROUP_NAMESPACE_LENGTH" to 128,
                "MAX_KAFKA_KEEPER_PATH_PREFIX_LENGTH" to 512,
            )
        )
    }

    @Test
    fun `should expose the cluster value limit`() {
        val companion = requireNotNull(ClickHouseTopology.Cluster::class.companionObject)

        companion.visibility.assert().isEqualTo(KVisibility.PUBLIC)
        companion.declaredMemberProperties
            .associate { it.name to it.visibility }
            .assert()
            .isEqualTo(mapOf("MAX_VALUE_LENGTH" to KVisibility.PUBLIC))
        ClickHouseTopology.Cluster.MAX_VALUE_LENGTH.assert().isEqualTo(128)
    }

    @Test
    fun `should not expose validation helpers through JVM file facades`() {
        listOf(
            "me.ahoo.wow.bi.BiScriptOptionsKt",
            "me.ahoo.wow.bi.ClickHouseTopologyKt",
        ).mapNotNull { className ->
            runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull()
        }.flatMap { fileFacade ->
            fileFacade.declaredMethods.filter { Modifier.isPublic(it.modifiers) }
        }.assert().isEmpty()
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
