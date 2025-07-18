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

package me.ahoo.wow.compiler.metadata

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import me.ahoo.wow.api.annotation.BoundedContext
import me.ahoo.wow.configuration.Aggregate
import me.ahoo.wow.configuration.WowMetadata
import kotlin.reflect.KClass

object BoundedContextResolver {

    fun KSClassDeclaration.resolveBoundedContext(): WowMetadata {
        val contextAnnotation = requireNotNull(getAnnotation(BoundedContext::class))
        val contextName = contextAnnotation.getName()
        val contextAlias = contextAnnotation.getAlias().ifBlank { null }
        val description = contextAnnotation.getDescription()
        val contextScopes = contextAnnotation.getScopes()
        val contextPackageScopes = contextAnnotation.getPackageScopes()
        val mergedContextScopes = linkedSetOf<String>().apply {
            addAll(contextPackageScopes)
            addAll(contextScopes)
            add(packageName.asString())
        }

        val contextAggregates = contextAnnotation.getAggregates().associate {
            val id = it.getArgumentValue<String>(BoundedContext.Aggregate::id.name).ifBlank { null }
            val tenantId = it.getArgumentValue<String>(BoundedContext.Aggregate::tenantId.name).ifBlank { null }
            val mergedAggregateScopes = it.getPackageScopes().plus(it.getScopes()).let {
                linkedSetOf<String>().apply {
                    addAll(it)
                }
            }
            it.getName() to Aggregate(tenantId = tenantId, id = id, scopes = mergedAggregateScopes)
        }
        val boundedContext = me.ahoo.wow.configuration.BoundedContext(
            alias = contextAlias,
            description = description,
            scopes = mergedContextScopes,
            aggregates = contextAggregates,
        )
        return WowMetadata(contexts = mapOf(contextName to boundedContext))
    }

    private fun KSAnnotation.getAggregates(): Set<KSAnnotation> {
        return getArgumentValue<List<KSAnnotation>>(BoundedContext::aggregates.name).toSet()
    }

    private fun KSAnnotation.getPackageScopes(): List<String> {
        return getArgumentValue<List<KSType>>(BoundedContext::packageScopes.name).map {
            it.declaration.packageName.asString()
        }
    }

    private fun KSAnnotation.getScopes(): List<String> {
        return getArgumentValue(BoundedContext::scopes.name)
    }

    private fun KSAnnotation.getName(): String {
        return getArgumentValue(BoundedContext::name.name)
    }

    private fun KSAnnotation.getAlias(): String {
        return getArgumentValue(BoundedContext::alias.name)
    }

    private fun KSAnnotation.getDescription(): String {
        return getArgumentValue(BoundedContext::description.name)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> KSAnnotation.getArgumentValue(name: String): T {
        return arguments.first { it.name!!.asString() == name }.value as T
    }

    fun KSAnnotated.getAnnotation(annotationKClass: KClass<*>): KSAnnotation? {
        return annotations.filter {
            it.shortName.getShortName() == annotationKClass.simpleName && it.annotationType.resolve().declaration
                .qualifiedName?.asString() == annotationKClass.qualifiedName
        }.firstOrNull()
    }
}
