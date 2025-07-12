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

package me.ahoo.wow.compiler.aggregate.metadata

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import me.ahoo.wow.compiler.AggregateRootResolver.resolveAggregateRootMetadata
import me.ahoo.wow.compiler.AggregateRootResolver.resolveDependencies
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object AggregatesMetadataResolver {
    fun KSClassDeclaration.resolveNamedAggregates(
        commandAggregates: List<KSClassDeclaration>
    ): AggregatesMetadataFile? {
        val packageName = packageName.asString()
        val aggregateMetadata = commandAggregates.filter {
            it.packageName.asString().startsWith(packageName)
        }.map {
            it.resolveAggregateRootMetadata()
        }
        if (aggregateMetadata.isEmpty()) {
            return null
        }
        val codeGenerator = StringBuilder()
        codeGenerator.appendLine("package $packageName")
        codeGenerator.appendLine()
        aggregateMetadata.forEach {
            codeGenerator.appendLine("import ${it.command.qualifiedName!!.asString()}")
            if (it.command.qualifiedName!!.asString() != it.state.qualifiedName!!.asString()) {
                codeGenerator.appendLine("import ${it.state.qualifiedName!!.asString()}")
            }
        }
        codeGenerator.appendLine("import me.ahoo.wow.modeling.annotation.aggregateMetadata")
        codeGenerator.appendLine("import javax.annotation.processing.Generated")
        codeGenerator.appendLine()
        val generatedDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        codeGenerator.appendLine(
            "@Generated(\"${AggregatesMetadataFile.Companion.GENERATOR_NAME}\", date = \"$generatedDate\")"
        )
        codeGenerator.appendLine("object ${AggregatesMetadataFile.Companion.FILE_NAME} {")
        aggregateMetadata.forEach {
            codeGenerator.appendLine(
                "    val ${it.command.simpleName.asString()}AggregateMetadata = aggregateMetadata<${it.command.simpleName.asString()}, ${it.state.simpleName.asString()}>()"
            )
        }
        codeGenerator.appendLine("}")

        val dependencies =
            Dependencies(
                aggregating = true,
                sources = aggregateMetadata.flatMap {
                    it.resolveDependencies()
                }.toTypedArray()
            )
        return AggregatesMetadataFile(
            dependencies = dependencies,
            packageName = packageName,
            code = codeGenerator.toString(),
        )
    }

    fun AggregatesMetadataFile.writeFile(codeGenerator: CodeGenerator) {
        val file = codeGenerator
            .createNewFile(
                dependencies = this.dependencies,
                packageName = this.packageName,
                fileName = AggregatesMetadataFile.Companion.FILE_NAME,
                extensionName = this.extensionName,
            )
        file.write(this.code.toByteArray())
        file.close()
    }
}

data class AggregatesMetadataFile(
    val dependencies: Dependencies,
    val packageName: String,
    val code: String,
    val extensionName: String = "kt"
) {

    companion object {
        const val FILE_NAME = "AggregatesMetadata"
        const val GENERATOR_NAME = "me.ahoo.wow.compiler.aggregate.metadata.AggregatesMetadataResolver"
    }
}
