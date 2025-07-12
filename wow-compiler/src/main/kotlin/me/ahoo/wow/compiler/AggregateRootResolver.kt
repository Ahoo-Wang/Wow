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

package me.ahoo.wow.compiler

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.naming.NamingConverter

object AggregateRootResolver {
    val AGGREGATE_ROOT_NAME = AggregateRoot::class.qualifiedName!!

    @OptIn(KspExperimental::class)
    fun KSClassDeclaration.toName(): String {
        return getAnnotationsByType(Name::class).firstOrNull()?.value
            ?: NamingConverter.PASCAL_TO_SNAKE.convert(simpleName.asString())
    }

    fun KSClassDeclaration.resolveAggregateRootMetadata(): AggregateRootMetadata {
        val aggregateRootCtor = primaryConstructor ?: getConstructors().firstOrNull()
        check(
            aggregateRootCtor != null && (aggregateRootCtor.parameters.size == 1 || aggregateRootCtor.parameters.size == 2)
        ) {
            "AggregateRoot[${qualifiedName!!.asString()}] must have a primary constructor with one parameter,like ctor(id) / ctor(id,tenantId) or ctor(state)."
        }

        val ctorParameterDeclaration = aggregateRootCtor.parameters.single().type.resolve().declaration
        val aggregationPattern = ctorParameterDeclaration.qualifiedName!!.asString() != String::class.qualifiedName!!

        val stateAggregateDeclaration = if (aggregationPattern) {
            (ctorParameterDeclaration as KSClassDeclaration)
        } else {
            this
        }

        return AggregateRootMetadata(this.toName(), this, stateAggregateDeclaration)
    }

    fun AggregateRootMetadata.resolveDependencies(): List<KSFile> {
        return buildList {
            command.containingFile?.let {
                add(it)
            }
            state.containingFile?.let {
                add(it)
            }
        }.distinct()
    }
}

data class AggregateRootMetadata(
    override val name: String,
    val command: KSClassDeclaration,
    val state: KSClassDeclaration
) : Named {
    val type: String
        get() = command.qualifiedName!!.asString()
}
