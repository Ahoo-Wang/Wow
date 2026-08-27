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

package me.ahoo.wow.schema.kotlin

import com.fasterxml.classmate.ResolvedType
import com.fasterxml.classmate.members.ResolvedMethod
import com.fasterxml.jackson.annotation.JsonIgnore
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.MemberScope.DeclarationDetails
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.infra.reflection.AnnotationScanner.scanAnnotation
import me.ahoo.wow.schema.JsonSchema.Companion.asCustomDefinition
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName
import me.ahoo.wow.schema.Types.isKotlinElement
import me.ahoo.wow.schema.Types.isStdType
import me.ahoo.wow.schema.Types.isWowType
import tools.jackson.databind.node.ObjectNode
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaType

object KotlinCustomDefinitionProvider : CustomDefinitionProviderV2 {
    private val cachedTypes = mutableSetOf<ResolvedType>()
    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext
    ): CustomDefinition? {
        if (!javaType.erasedType.isKotlinElement() || javaType.erasedType.isStdType() || javaType.erasedType.isWowType()) {
            return null
        }

        val kotlinGettersIfNonFields = javaType.erasedType.kotlin.memberProperties.filter {
            it.visibility == KVisibility.PUBLIC &&
                it.javaField == null &&
                it.scanAnnotation<JsonIgnore>()?.value != true &&
                it.scanAnnotation<Schema>()?.hidden != true
        }
        if (kotlinGettersIfNonFields.isEmpty()) {
            return null
        }
        if (cachedTypes.contains(javaType)) {
            return null
        }
        val declarationDetails = DeclarationDetails(javaType, context.typeContext.resolveWithMembers(javaType))
        cachedTypes.add(javaType)
        val rootSchema = context.createStandardDefinition(javaType, this).asJsonSchema()
        rootSchema.ensureProperties()
        val propertiesNode: ObjectNode = rootSchema.getProperties() ?: return null
        for (kotlinGetter in kotlinGettersIfNonFields) {
            if (propertiesNode.get(kotlinGetter.name) == null) {
                val kotlinGetterMethod = declarationDetails.declaringTypeMembers.memberMethods.firstOrNull {
                    it.name === kotlinGetter.javaGetter!!.name
                } ?: continue
                val resolvedMethod = copyResolvedMethod(kotlinGetter, kotlinGetterMethod, context)
                val methodScope: MethodScope =
                    context.typeContext.createMethodScope(resolvedMethod, declarationDetails)
                val getterNode = createStandardDefinition(methodScope, context)
                val readOnly = SchemaKeyword.TAG_READ_ONLY.toPropertyName()
                getterNode.put(readOnly, true)
                propertiesNode.set(kotlinGetter.name, getterNode)
            }
        }
        return rootSchema.asCustomDefinition()
    }

    private fun createStandardDefinition(
        methodScope: MethodScope,
        context: SchemaGenerationContext
    ): ObjectNode {
        return if (context.generatorConfig.shouldInlineAllSchemas()) {
            context.createStandardDefinition(methodScope, null) as ObjectNode
        } else {
            context.createStandardDefinitionReference(methodScope, null) as ObjectNode
        }
    }

    private fun copyResolvedMethod(
        kotlinGetter: KProperty1<*, *>,
        resolvedMethod: ResolvedMethod,
        context: SchemaGenerationContext
    ): ResolvedMethod {
        val returnType = context.typeContext.resolve(kotlinGetter.returnType.javaType)
        val argumentTypes: Array<ResolvedType> = Array(resolvedMethod.argumentCount) {
            resolvedMethod.getArgumentType(it)
        }
        return ResolvedMethod(
            resolvedMethod.declaringType,
            resolvedMethod.annotations,
            resolvedMethod.rawMember,
            returnType,
            argumentTypes
        )
    }

    override fun resetAfterSchemaGenerationFinished() {
        this.cachedTypes.clear()
    }
}
