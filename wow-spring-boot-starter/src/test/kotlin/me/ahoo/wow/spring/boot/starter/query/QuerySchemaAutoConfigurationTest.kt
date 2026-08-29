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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.ClasspathQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.schema.WorkingDirectoryQuerySchemaSource
import me.ahoo.wow.schema.query.JsonQuerySchemaSource
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.spring.boot.starter.enableWow
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Base64

class QuerySchemaAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `should use compatible validation by default`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
            .run { context ->
                context.getBean(QueryProperties::class.java)
                    .schema.validationMode.assert().isEqualTo(QuerySchemaValidationMode.COMPATIBLE)
                context.getBean(QueryProperties::class.java).cursor.encryptionKey.assert().isNull()
                context.getBean(QueryProperties::class.java).cursorTokenCodec().assert().isNull()
            }
    }

    @Test
    fun `should bind cursor encryption key and create a usable codec`() {
        val key = encodedKey(1)
        contextRunner
            .enableWow()
            .withPropertyValues("wow.query.cursor.encryption-key=$key")
            .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
            .run { context ->
                val properties = context.getBean(QueryProperties::class.java)
                val payload = "payload".toByteArray()
                val codec = properties.cursorTokenCodec()!!

                properties.cursor.encryptionKey.assert().isEqualTo(key)
                codec.decode(codec.encode(payload)).contentEquals(payload).assert().isTrue()
            }
    }

    @Test
    fun `should fail configuration for an invalid nonblank cursor encryption key`() {
        listOf(
            "not-base64!",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31)),
        ).forEach { key ->
            contextRunner
                .enableWow()
                .withPropertyValues("wow.query.cursor.encryption-key=$key")
                .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
                .run { context -> context.startupFailure.assert().isNotNull() }
        }
    }

    @Test
    fun `should bind strict schema validation mode`() {
        contextRunner
            .enableWow()
            .withPropertyValues("wow.query.schema.validation-mode=STRICT")
            .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
            .run { context ->
                context.getBean(QueryProperties::class.java)
                    .schema.validationMode.assert().isEqualTo(QuerySchemaValidationMode.STRICT)
            }
    }

    @Test
    fun `should expose mutable enum query schema properties`() {
        val properties = QueryProperties(
            schema = QueryProperties.Schema(
                validationMode = QuerySchemaValidationMode.COMPATIBLE,
            ),
        )
        properties.schema = QueryProperties.Schema(
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
        )
        properties.schema.validationMode = QuerySchemaValidationMode.STRICT

        properties.schema.validationMode.assert().isEqualTo(QuerySchemaValidationMode.STRICT)
    }

    @Test
    fun `should expose built in schema sources`() {
        contextRunner
            .enableWow()
            .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
            .run { context ->
                context.assert().hasNotFailed().doesNotHaveBean(QueryModelSchema::class.java)
                context.getBeansOfType(QuerySchemaSource::class.java).values
                    .map { it::class }
                    .assert()
                    .containsExactlyInAnyOrder(
                        JsonQuerySchemaSource::class,
                        WorkingDirectoryQuerySchemaSource::class,
                        ClasspathQuerySchemaSource::class,
                        BeanQuerySchemaSource::class,
                    )
            }
    }

    @Test
    fun `should make all schema registration beans available to bean source`() {
        val context = QuerySchemaContext(MOCK_AGGREGATE_METADATA, QueryModel.SNAPSHOT)
        contextRunner
            .enableWow()
            .withBean("firstRegistration", QuerySchemaRegistration::class.java, {
                QuerySchemaRegistration(context, QuerySchemaDeclaration(emptyMap()))
            })
            .withBean("secondRegistration", QuerySchemaRegistration::class.java, {
                QuerySchemaRegistration(context, QuerySchemaDeclaration(emptyMap()))
            })
            .withUserConfiguration(QuerySchemaAutoConfiguration::class.java)
            .run { applicationContext ->
                applicationContext.getBean(BeanQuerySchemaSource::class.java)
                    .load(context)
                    .collectList()
                    .block()!!
                    .assert()
                    .hasSize(2)
            }
    }

    @Test
    fun `should publish one compatible validation default in canonical metadata`() {
        val propertyName = "wow.query.schema.validation-mode"
        val metadata = metadataProperties("META-INF/spring-configuration-metadata.json")
        val canonical = metadata
            .filter { it.path("name").stringValue() == propertyName }
        canonical.assert().hasSize(1)
        canonical.single().path("type").stringValue()
            .assert().isEqualTo("me.ahoo.wow.query.schema.QuerySchemaValidationMode")
        canonical.single().path("defaultValue").stringValue().assert().isEqualTo("COMPATIBLE")

        metadata.filter { it.path("name").stringValue() == QueryProperties.CURSOR_ENCRYPTION_KEY }
            .single().path("type").stringValue().assert().isEqualTo("java.lang.String")

        metadataProperties("META-INF/additional-spring-configuration-metadata.json")
            .filter { it.path("name").stringValue() == propertyName }
            .assert()
            .isEmpty()
    }

    private fun metadataProperties(resourceName: String) = JsonSerializer.readTree(
        requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Missing metadata resource [$resourceName]."
        }.bufferedReader().use { it.readText() },
    ).path("properties").asSequence().toList()

    private fun encodedKey(seed: Int): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32) { index -> (seed + index).toByte() })
}
