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

package me.ahoo.wow.schema.query

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.InferredQuerySchemaSource
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class SensitiveValueTypeTest {
    private val context = QuerySchemaContext(MaterializedNamedAggregate("test", "test"), QueryModel.SNAPSHOT)

    @Test
    fun `a sensitive value type protects every property declared with it`() {
        val state = load(ValueTypeState::class.java)
        val phone = MaskRule(SensitivityLevel.DISPLAY, Mask(keepPrefix = 3, keepSuffix = 2))

        state.property("phone").maskRule.assert().isEqualTo(DeclarationValue.Set(phone))
        state.property("phones").maskRule.assert().isEqualTo(DeclarationValue.Set(phone))
        state.property(
            "idCard"
        ).maskRule.assert().isEqualTo(DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL)))
        state.property("plain").maskRule.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `a sensitive value type is found through maps and nested collections`() {
        val state = load(ContainerValueTypeState::class.java)
        val phone = DeclarationValue.Set(MaskRule(SensitivityLevel.DISPLAY, Mask(keepPrefix = 3, keepSuffix = 2)))

        state.property("phoneBook").additionalProperties.value().maskRule.assert().isEqualTo(phone)
        state.property("phoneGroups").maskRule.assert().isEqualTo(phone)
        state.property("listBook").additionalProperties.value().maskRule.assert().isEqualTo(phone)
        val idBook = state.property("idBook")
        val idValues = (
            idBook.alternatives.value().singleOrNull { it.additionalProperties is DeclarationValue.Set }
                ?: idBook
            ).additionalProperties.value()
        idValues.maskRule.assert().isEqualTo(DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL)))
    }

    @Test
    fun `a property may tighten but never loosen its value type`() {
        load(ValueTypeState::class.java).property("tightened").maskRule.assert()
            .isEqualTo(DeclarationValue.Set(MaskRule(SensitivityLevel.CONFIDENTIAL)))
        assertThrows<QuerySchemaConflictException> { load(LoosenedValueTypeState::class.java) }
            .message.assert().contains("loosens its value type").contains("IdCardNumber")
    }

    @Test
    fun `sensitive types that do not serialize as a string are rejected`() {
        assertThrows<QuerySchemaConflictException> { load(NonValueTypeState::class.java) }
            .message.assert().contains("NotAValueType").contains("must serialize as a string")
    }

    @Test
    fun `state and event share the value type and mismatched leaves are only warned about`() {
        val namedAggregate = ContactAggregate::class.java.aggregateMetadata<Any, Any>().namedAggregate
        val source = InferredQuerySchemaSource(JsonQueryModelSource())
        val snapshot = source.load(QuerySchemaContext(namedAggregate, QueryModel.SNAPSHOT)).single().block()!!
        val phone = DeclarationValue.Set(MaskRule(SensitivityLevel.DISPLAY, Mask(keepPrefix = 3, keepSuffix = 2)))
        snapshot.fields.getValue(QueryField("state")).properties.valueOr().getValue("phone").maskRule.assert()
            .isEqualTo(phone)

        val (events, warnings) = captureWarnings {
            source.load(QuerySchemaContext(namedAggregate, QueryModel.EVENT_STREAM)).single().block()!!
        }

        events.fields.getValue(QueryField("body.body")).properties.valueOr().getValue("phone").maskRule.assert()
            .isEqualTo(phone)
        warnings.assert().hasSize(2)
        warnings.single { "mobile" in it }.assert().contains("[state.mobile] is DISPLAY")
            .contains("[body.body<ContactChanged>.mobile] is not sensitive")
        warnings.single { "email" in it }.assert().contains("[state.email] is not sensitive")
            .contains("[body.body<ContactChanged>.email] is DISPLAY")
    }

    private fun load(type: Class<*>): QuerySchemaDeclaration =
        InferredQuerySchemaSource(JsonQueryModelSource(), typeResolver = { type }).load(context).single().block()!!

    private fun QuerySchemaDeclaration.property(name: String): QueryFieldDeclaration =
        fields.getValue(QueryField("state")).properties.valueOr().getValue(name)

    private fun <T : Any> DeclarationValue<T?>.value(): T = checkNotNull((this as DeclarationValue.Set).value)

    private fun <T> DeclarationValue<Map<String, T>>.valueOr(): Map<String, T> =
        (this as? DeclarationValue.Set)?.value.orEmpty()

    private fun <T> captureWarnings(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger("me.ahoo.wow.query.schema") as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        return try {
            block() to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
