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

package me.ahoo.wow.manifest.tools

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.QueryAlias
import me.ahoo.wow.api.query.annotation.QueryIndex
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.QueryText
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.manifest.Manifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * The query annotations a property inherits from the members it overrides ([OverriddenMembers], [QueryFieldKeywords]):
 * `@Sensitive` of any of them holds and the property can only tighten it; every other annotation is the property's
 * own when it declares one, else the one the overridden members agree on, and a disagreement fails the build.
 */
class QueryKeywordsInheritanceTest {
    private val confidential = mapOf("level" to "confidential")
    private val noMask = mapOf("keepPrefix" to 0, "keepSuffix" to 0)

    // The case of the issue: a read model interface keeps the annotations on its getters; the state overrides them.
    interface StorageConfig {
        @get:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val accessKeySecret: String

        @get:QueryTemporal(unit = TimeUnit.SECONDS)
        val updatedAt: Long
    }

    class StorageConfigState : StorageConfig {
        override var accessKeySecret: String = ""
        override var updatedAt: Long = 0
    }

    @Test
    fun `a state should inherit the annotations of its interface's getters`() {
        sensitive(StorageConfigState::class.java, "accessKeySecret").assert().isEqualTo(confidential)
        property(StorageConfigState::class.java, "accessKeySecret")["x-wow-mask"].assert().isEqualTo(noMask)
        property(StorageConfigState::class.java, "updatedAt")["x-wow-temporal"].assert()
            .isEqualTo(mapOf("unit" to "seconds"))
    }

    class ComputedState : StorageConfig {
        override val accessKeySecret: String get() = "secret"
        override val updatedAt: Long get() = 1
    }

    @Test
    fun `a computed property should inherit them too`() {
        sensitive(ComputedState::class.java, "accessKeySecret").assert().isEqualTo(confidential)
        property(ComputedState::class.java, "updatedAt")["x-wow-temporal"].assert()
            .isEqualTo(mapOf("unit" to "seconds"))
    }

    class JavaReadModelState(private val secret: String, private val created: Long) : JavaReadModel {
        override fun getAccessKeySecret(): String = secret

        override fun getCreatedAt(): Long = created
    }

    @Test
    fun `a state should inherit the annotations of a Java interface's getters`() {
        sensitive(JavaReadModelState::class.java, "accessKeySecret").assert().isEqualTo(confidential)
        property(JavaReadModelState::class.java, "createdAt")["x-wow-temporal"].assert()
            .isEqualTo(mapOf("unit" to "seconds"))
    }

    abstract class BaseState {
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepSuffix = 4))
        open val phone: String = ""

        @get:QueryAlias("state.holder")
        abstract val owner: String
    }

    class ChildState : BaseState() {
        override val phone: String = "13800138000"
        override val owner: String = "ann"
    }

    @Test
    fun `a state should inherit the annotations of its superclass's property, its field or getter`() {
        sensitive(ChildState::class.java, "phone").assert().isEqualTo(mapOf("level" to "display"))
        property(ChildState::class.java, "phone")["x-wow-mask"].assert()
            .isEqualTo(mapOf("keepPrefix" to 0, "keepSuffix" to 4))
        property(ChildState::class.java, "owner")["x-wow-alias"].assert().isEqualTo(listOf("state.holder"))
    }

    interface Auditable {
        @get:QueryTemporal(unit = TimeUnit.SECONDS)
        val createdAt: Long
    }

    interface Versioned : Auditable

    interface Stamped : Auditable

    abstract class Record : Versioned

    // Auditable is reached twice, through Versioned and through Stamped.
    class DeepState : Record(), Stamped {
        override val createdAt: Long = 0
    }

    @Test
    fun `the annotations should be inherited through several levels of interfaces and superclasses`() {
        property(DeepState::class.java, "createdAt")["x-wow-temporal"].assert().isEqualTo(mapOf("unit" to "seconds"))
    }

    interface Holder<T> {
        @get:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val secret: T
    }

    class GenericState : Holder<String> {
        override val secret: String = ""
    }

    @Test
    fun `a generic interface's annotation should be inherited by the property that gives its type`() {
        sensitive(GenericState::class.java, "secret").assert().isEqualTo(confidential)
    }

    @Target(AnnotationTarget.PROPERTY_GETTER)
    @Retention(AnnotationRetention.RUNTIME)
    @Sensitive(SensitivityLevel.CONFIDENTIAL)
    annotation class GetterSecret

    interface Carrying {
        @get:GetterSecret
        val token: String
    }

    class CarryingState : Carrying {
        override val token: String = ""
    }

    @Test
    fun `an annotation class an overridden getter carries should be inherited as well`() {
        sensitive(CarryingState::class.java, "token").assert().isEqualTo(confidential)
    }

    data class OwnTemporalState(
        @get:QueryTemporal(unit = TimeUnit.MILLISECONDS)
        override val createdAt: Long,
    ) : Auditable

    @Test
    fun `a property that declares the annotation itself should keep its own`() {
        property(OwnTemporalState::class.java, "createdAt")["x-wow-temporal"].assert()
            .isEqualTo(mapOf("unit" to "milliseconds"))
    }

    interface Masked {
        @get:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1))
        val phone: String
    }

    class TightenedState : Masked {
        @field:Sensitive(SensitivityLevel.CONFIDENTIAL, mask = Mask(keepSuffix = 2))
        override val phone: String = ""
    }

    class RepeatedState : Masked {
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1))
        override val phone: String = ""
    }

    class LoosenedState : Holder<String> {
        @field:Sensitive(SensitivityLevel.DISPLAY)
        override val secret: String = ""
    }

    @Test
    fun `a property may tighten or repeat an inherited sensitivity, never loosen it`() {
        sensitive(TightenedState::class.java, "phone").assert().isEqualTo(confidential)
        property(TightenedState::class.java, "phone")["x-wow-mask"].assert()
            .isEqualTo(mapOf("keepPrefix" to 0, "keepSuffix" to 2))
        sensitive(RepeatedState::class.java, "phone").assert().isEqualTo(mapOf("level" to "display"))
        failure(LoosenedState::class.java).assert().isEqualTo(
            "@Sensitive on ${LoosenedState::class.java.name}.secret loosens ${Holder::class.java.name}.secret's " +
                "CONFIDENTIAL to DISPLAY: an overridden member's sensitivity holds, a property may tighten it.",
        )
    }

    class NumberState : Holder<Int> {
        override val secret: Int = 0
    }

    @Test
    fun `an inherited sensitivity should still need a String property`() {
        failure(NumberState::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${NumberState::class.java.name}.secret: a sensitive field must have the String JVM type."
            )
    }

    interface Left {
        @get:Sensitive(SensitivityLevel.DISPLAY)
        val moment: String
    }

    interface Right {
        @get:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val moment: String
    }

    class ConflictState : Left, Right {
        override val moment: String = ""
    }

    @Test
    fun `overridden members that disagree should fail the build, naming both`() {
        val message = failure(ConflictState::class.java)
        message.assert().startsWith(
            "@Sensitive on ${ConflictState::class.java.name}.moment is inherited from the members it overrides, " +
                "which disagree: ${Left::class.java.name}.moment declares DISPLAY and " +
                "${Right::class.java.name}.moment declares CONFIDENTIAL.",
        )
    }

    interface LeftTime {
        @get:QueryTemporal(unit = TimeUnit.SECONDS)
        val moment: Long
    }

    interface RightTime {
        @get:QueryTemporal(unit = TimeUnit.MILLISECONDS)
        val moment: Long
    }

    interface SameTime {
        @get:QueryTemporal(unit = TimeUnit.SECONDS)
        val moment: Long
    }

    class DisagreeingState : LeftTime, RightTime {
        override val moment: Long = 0
    }

    class AgreeingState : LeftTime, SameTime {
        override val moment: Long = 0
    }

    class ChosenState : LeftTime, RightTime {
        @get:QueryTemporal(unit = TimeUnit.MICROSECONDS)
        override val moment: Long = 0
    }

    @Test
    fun `members that disagree on another annotation should fail unless the property declares it`() {
        val message = failure(DisagreeingState::class.java)
        message.assert().startsWith("A semantic type on ${DisagreeingState::class.java.name}.moment is inherited")
        message.assert().contains(
            "${LeftTime::class.java.name}.moment declares @QueryTemporal(",
            "${RightTime::class.java.name}.moment declares @QueryTemporal(",
            "Declare it on ${DisagreeingState::class.java.name}.moment to say which holds.",
        )
        property(AgreeingState::class.java, "moment")["x-wow-temporal"].assert().isEqualTo(mapOf("unit" to "seconds"))
        property(ChosenState::class.java, "moment")["x-wow-temporal"].assert()
            .isEqualTo(mapOf("unit" to "microseconds"))
    }

    interface Searched {
        @get:QueryText
        val note: String

        @get:QueryAlias("state.remark", "state.memo")
        val remark: String

        @get:QueryIndex(name = "by_title", with = ["lastEventTime"])
        val title: String
    }

    @QueryIndex(name = "by_status", on = "state.status")
    class SearchedState : Searched {
        override val note: String = ""
        override val remark: String = ""
        override val title: String = ""
        val status: String = ""
    }

    @Test
    fun `a state should inherit its text, aliases and property indexes too`() {
        property(SearchedState::class.java, "note")["x-wow-text"].assert().isEqualTo(emptyMap<String, Any>())
        property(SearchedState::class.java, "remark")["x-wow-alias"].assert()
            .isEqualTo(listOf("state.remark", "state.memo"))
        state(SearchedState::class.java)["x-wow-index"].assert().isEqualTo(
            listOf(
                mapOf("name" to "by_status", "keys" to listOf("state.status")),
                mapOf("name" to "by_title", "keys" to listOf("state.title", "lastEventTime")),
            ),
        )
    }

    class PlainState(val status: String)

    @Test
    fun `a property that overrides nothing should carry no inherited keyword`() {
        property(PlainState::class.java, "status").keys.assert().isEqualTo(setOf("type"))
    }

    /** A state of an interface's annotations the end-to-end test of the query engine reads (`inherited-state.defs.json`). */
    class InheritedState : StorageConfig {
        override var accessKeySecret: String = ""
        override var updatedAt: Long = 0
        var name: String = ""
    }

    @Test
    fun `the inherited state schema should be the snapshot the query engine reads`() {
        val snapshot = javaClass.getResourceAsStream("/query-keywords/inherited-state.defs.json")!!.use {
            tools.jackson.module.kotlin.jacksonObjectMapper().readValue(it, Map::class.java)
        }
        complete(InheritedState::class.java).defs.assert().isEqualTo(snapshot)
    }

    @Test
    fun `an existing service's baseline should report the keywords a property now inherits as breaking`() {
        // The Manifest recorded before the inheritance has neither keyword; the one built now has both.
        val before = mapOf("type" to "string")
        val now = mapOf(
            "type" to "string",
            "x-wow-sensitive" to mapOf("level" to "confidential"),
            "x-wow-mask" to mapOf("keepPrefix" to 0, "keepSuffix" to 0),
        )
        SchemaCompatibility(mapOf("t" to before), mapOf("t" to now)).compare("s", "#/\$defs/t", "#/\$defs/t")
            .map { it.toString() }.assert().containsExactlyInAnyOrder(
                "BREAKING s: x-wow-mask changed from null to {keepPrefix=0, keepSuffix=0}",
                "BREAKING s: x-wow-sensitive changed from null to {level=confidential}",
            )
    }

    private val introspector = SchemaIntrospector(javaClass.classLoader)

    private fun complete(state: Class<*>): Manifest = introspector.complete(QueryKeywordsTest.fragment(state))

    private fun state(state: Class<*>): Map<*, *> = complete(state).defs[state.name] as Map<*, *>

    private fun property(state: Class<*>, name: String): Map<*, *> =
        (state(state)["properties"] as Map<*, *>)[name] as Map<*, *>

    private fun sensitive(state: Class<*>, name: String): Any? = property(state, name)["x-wow-sensitive"]

    private fun failure(state: Class<*>): String? = assertThrows<IllegalStateException> { complete(state) }.message
}
