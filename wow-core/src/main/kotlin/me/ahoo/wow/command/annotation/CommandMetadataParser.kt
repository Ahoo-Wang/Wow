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

package me.ahoo.wow.command.annotation

import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateNameGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toAggregateVersionGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toOwnerIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStaticAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStaticTenantIdGetterIfAnnotated
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toStringGetter
import me.ahoo.wow.annotation.AnnotationPropertyAccessorParser.toTenantIdGetterIfAnnotated
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.DEFAULT_AGGREGATE_ID_NAME
import me.ahoo.wow.api.annotation.VoidCommand
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.matedata.MetadataNamedAggregateGetter
import me.ahoo.wow.modeling.matedata.NamedAggregateGetter
import me.ahoo.wow.modeling.matedata.SelfNamedAggregateGetter
import me.ahoo.wow.modeling.matedata.toNamedAggregateGetter
import me.ahoo.wow.naming.annotation.toName
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure

/**
 * Parser for extracting command metadata from command classes.
 *
 * This parser analyzes command classes to extract metadata such as aggregate information,
 * property getters for IDs and versions, and command characteristics like create commands
 * or void commands.
 *
 * @author ahoo wang
 * @see CommandMetadata
 * @see CacheableMetadataParser
 */
object CommandMetadataParser : CacheableMetadataParser() {
    /**
     * Parses the given command class to extract command metadata.
     *
     * This method uses reflection to analyze the command class and its properties,
     * extracting information about aggregate targeting, property accessors, and
     * command characteristics.
     *
     * @param TYPE the type of the command class
     * @param M the metadata type (should be CommandMetadata)
     * @param type the Class object representing the command type
     * @return the parsed CommandMetadata for the command class
     * @see CommandMetadata
     * @see CommandMetadataVisitor
     */
    override fun <TYPE : Any, M : Metadata> parseToMetadata(type: Class<TYPE>): M {
        val visitor = CommandMetadataVisitor(type)
        type.kotlin.visit(visitor)
        @Suppress("UNCHECKED_CAST")
        return visitor.toMetadata() as M
    }
}

/**
 * Visitor class for extracting command metadata during class reflection.
 *
 * This internal class implements the visitor pattern to analyze command classes
 * and extract relevant metadata such as property getters and command characteristics.
 *
 * @param C the type of the command class being visited
 * @property commandType the Class object of the command type
 * @author ahoo wang
 * @see ClassVisitor
 * @see CommandMetadata
 */
internal class CommandMetadataVisitor<C>(
    private val commandType: Class<C>
) : ClassVisitor<C, CommandMetadata<C>> {
    private val commandName: String = commandType.toName()
    private val isCreate = commandType.isAnnotationPresent(CreateAggregate::class.java)
    private val isVoid = commandType.isAnnotationPresent(VoidCommand::class.java)
    private var allowCreate: Boolean = commandType.isAnnotationPresent(AllowCreate::class.java)
    private var namedAggregateGetter: NamedAggregateGetter<C>? = null
    private var aggregateNameGetter: PropertyGetter<C, String>? = null
    private var aggregateIdGetter: PropertyGetter<C, String>? = null
    private var namedIdProperty: KProperty1<C, String>? = null
    private var tenantIdGetter: PropertyGetter<C, String>? = null
    private var ownerIdGetter: PropertyGetter<C, String>? = null
    private var aggregateVersionGetter: PropertyGetter<C, Int?>? = null

    init {
        if (NamedAggregate::class.java.isAssignableFrom(commandType)) {
            @Suppress("UNCHECKED_CAST")
            namedAggregateGetter = SelfNamedAggregateGetter as NamedAggregateGetter<C>
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun visitType(type: KType) {
        if (aggregateIdGetter == null) {
            aggregateIdGetter = type.jvmErasure.toStaticAggregateIdGetterIfAnnotated() as PropertyGetter<C, String>?
        }
        if (tenantIdGetter == null) {
            tenantIdGetter = type.jvmErasure.toStaticTenantIdGetterIfAnnotated() as PropertyGetter<C, String>?
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun visitProperty(property: KProperty1<C, *>) {
        if (aggregateNameGetter == null) {
            aggregateNameGetter = property.toAggregateNameGetterIfAnnotated()
        }
        if (aggregateIdGetter == null) {
            aggregateIdGetter = property.toAggregateIdGetterIfAnnotated()
        }

        if (namedIdProperty == null && DEFAULT_AGGREGATE_ID_NAME == property.name) {
            namedIdProperty = property as KProperty1<C, String>?
        }
        if (tenantIdGetter == null) {
            tenantIdGetter = property.toTenantIdGetterIfAnnotated()
        }
        if (ownerIdGetter == null) {
            ownerIdGetter = property.toOwnerIdGetterIfAnnotated()
        }
        if (aggregateVersionGetter == null) {
            aggregateVersionGetter = property.toAggregateVersionGetterIfAnnotated() as PropertyGetter<C, Int?>?
        }
    }

    override fun end() {
        if (namedAggregateGetter == null) {
            namedAggregateGetter = aggregateNameGetter.toNamedAggregateGetter(commandType)
        }

        if (aggregateIdGetter != null || namedIdProperty == null) {
            return
        }
        aggregateIdGetter = namedIdProperty!!.toStringGetter()
    }

    override fun toMetadata(): CommandMetadata<C> {
        if (tenantIdGetter == null && namedAggregateGetter is MetadataNamedAggregateGetter) {
            val metadataNamedAggregateGetter = namedAggregateGetter as MetadataNamedAggregateGetter
            val tenantId = MetadataSearcher.requiredAggregate(metadataNamedAggregateGetter.namedAggregate).tenantId
            if (tenantId != null) {
                tenantIdGetter = StaticPropertyGetter(tenantId)
            }
        }
        return CommandMetadata(
            commandType = commandType,
            namedAggregateGetter = namedAggregateGetter,
            name = commandName,
            isCreate = isCreate,
            allowCreate = allowCreate,
            isVoid = isVoid,
            aggregateIdGetter = aggregateIdGetter,
            aggregateVersionGetter = aggregateVersionGetter,
            tenantIdGetter = tenantIdGetter,
            ownerIdGetter = ownerIdGetter,
        )
    }
}

/**
 * Extension function to get command metadata for a command class.
 *
 * This convenience function allows easy access to command metadata parsing
 * directly on Class objects.
 *
 * @param C the type of the command class
 * @return the CommandMetadata for this command class
 * @see CommandMetadataParser
 * @see CommandMetadata
 */
fun <C> Class<out C>.commandMetadata(): CommandMetadata<C> = CommandMetadataParser.parse(this)

/**
 * Inline function to get command metadata for a reified command type.
 *
 * This convenience function provides type-safe access to command metadata
 * using Kotlin's reified generics.
 *
 * @param C the reified type of the command class
 * @return the CommandMetadata for the command type
 * @see commandMetadata extension function
 */
inline fun <reified C> commandMetadata(): CommandMetadata<C> = C::class.java.commandMetadata()
