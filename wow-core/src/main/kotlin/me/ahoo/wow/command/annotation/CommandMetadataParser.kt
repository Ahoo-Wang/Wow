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

import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateNameGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toAggregateVersionGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toStaticAggregateIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toStaticTenantIdGetterIfAnnotated
import me.ahoo.wow.annotation.AggregateAnnotationParser.toStringGetter
import me.ahoo.wow.annotation.AggregateAnnotationParser.toTenantIdGetterIfAnnotated
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.DEFAULT_AGGREGATE_ID_NAME
import me.ahoo.wow.command.metadata.CommandMetadata
import me.ahoo.wow.configuration.MetadataSearcher
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.infra.accessor.property.StaticPropertyGetter
import me.ahoo.wow.infra.reflection.ClassMetadata.visit
import me.ahoo.wow.infra.reflection.ClassVisitor
import me.ahoo.wow.metadata.CacheableMetadataParser
import me.ahoo.wow.modeling.matedata.MetadataNamedAggregateGetter
import me.ahoo.wow.modeling.matedata.toNamedAggregateGetter
import me.ahoo.wow.naming.annotation.toName
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Method

private val LOG = LoggerFactory.getLogger(CommandMetadataParser::class.java)

/**
 * Command Metadata Parser .
 *
 * @author ahoo wang
 */
object CommandMetadataParser : CacheableMetadataParser<Class<*>, CommandMetadata<*>>() {

    override fun parseToMetadata(type: Class<*>): CommandMetadata<*> {
        val visitor = CommandMetadataVisitor(type)
        visit(type, visitor)
        return visitor.toMetadata()
    }
}

internal class CommandMetadataVisitor<C>(private val commandType: Class<C>) : ClassVisitor {
    private val commandName: String = commandType.toName()
    private val isCreateAggregate = commandType.isAnnotationPresent(CreateAggregate::class.java)
    private var allowCreate: Boolean = commandType.isAnnotationPresent(AllowCreate::class.java)
    private var aggregateNameGetter: PropertyGetter<C, String>? = null
    private var aggregateIdGetter: PropertyGetter<C, String>? = null
    private var namedIdField: Field? = null
    private var tenantIdGetter: PropertyGetter<C, String>? = null
    private var aggregateVersionGetter: PropertyGetter<C, Int?>? = null

    override fun visitClass(currentClass: Class<*>) {
        if (aggregateIdGetter == null) {
            aggregateIdGetter = currentClass.toStaticAggregateIdGetterIfAnnotated()
        }
        if (tenantIdGetter == null) {
            tenantIdGetter = currentClass.toStaticTenantIdGetterIfAnnotated()
        }
    }

    override fun visitField(field: Field) {
        if (aggregateNameGetter == null) {
            aggregateNameGetter = field.toAggregateNameGetterIfAnnotated()
        }
        if (aggregateIdGetter == null) {
            aggregateIdGetter = field.toAggregateIdGetterIfAnnotated()
        }

        if (namedIdField == null && DEFAULT_AGGREGATE_ID_NAME == field.name) {
            namedIdField = field
        }
        if (tenantIdGetter == null) {
            tenantIdGetter = field.toTenantIdGetterIfAnnotated()
        }
        if (aggregateVersionGetter == null) {
            aggregateVersionGetter = field.toAggregateVersionGetterIfAnnotated()
        }
    }

    override fun visitMethod(method: Method) {
        if (aggregateNameGetter == null) {
            aggregateNameGetter = method.toAggregateNameGetterIfAnnotated()
        }
        if (aggregateIdGetter == null) {
            aggregateIdGetter = method.toAggregateIdGetterIfAnnotated()
        }
        if (tenantIdGetter == null) {
            tenantIdGetter = method.toTenantIdGetterIfAnnotated()
        }
        if (aggregateVersionGetter == null) {
            aggregateVersionGetter = method.toAggregateVersionGetterIfAnnotated()
        }
    }

    override fun end() {
        if (aggregateIdGetter != null || namedIdField == null) {
            return
        }

        aggregateIdGetter = namedIdField!!.toStringGetter()
    }

    fun toMetadata(): CommandMetadata<C> {
        if (aggregateIdGetter == null && !isCreateAggregate) {
            if (LOG.isWarnEnabled) {
                LOG.warn(
                    "Command[$commandType] does not define an aggregate ID field and is not a create aggregate command.",
                )
            }
        }

        val namedAggregateGetter = aggregateNameGetter.toNamedAggregateGetter(commandType)
        if (tenantIdGetter == null && namedAggregateGetter is MetadataNamedAggregateGetter) {
            val tenantId = MetadataSearcher.requiredAggregate(namedAggregateGetter.namedAggregate).tenantId
            if (tenantId != null) {
                tenantIdGetter = StaticPropertyGetter(tenantId)
            }
        }
        return CommandMetadata(
            commandType = commandType,
            namedAggregateGetter = namedAggregateGetter,
            name = commandName,
            isCreate = isCreateAggregate,
            allowCreate = allowCreate,
            aggregateIdGetter = aggregateIdGetter,
            aggregateVersionGetter = aggregateVersionGetter,
            tenantIdGetter = tenantIdGetter,
        )
    }
}

fun <C> Class<out C>.toCommandMetadata(): CommandMetadata<C> {
    @Suppress("UNCHECKED_CAST")
    return CommandMetadataParser.parse(this) as CommandMetadata<C>
}

inline fun <reified C> commandMetadata(): CommandMetadata<C> {
    return C::class.java.toCommandMetadata()
}
