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

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.configureKsp
import me.ahoo.wow.compiler.SourceFiles.toSourceFile
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File

@OptIn(ExperimentalCompilerApi::class)
fun compileTest(
    sources: List<File>,
    symbolProcessorProvider: SymbolProcessorProvider,
    consumer: (KotlinCompilation, JvmCompilationResult) -> Unit = { _, _ ->
    }
) {
    val kotlinCompilation = KotlinCompilation().apply {
        inheritClassPath = true
        this.sources = sources.map { it.toSourceFile() }
        configureKsp(useKsp2 = true) {
            incremental = true
            symbolProcessorProviders += symbolProcessorProvider
        }
    }
    val result = kotlinCompilation.compile()
    assertThat(result.messages, result.exitCode, `is`(KotlinCompilation.ExitCode.OK))
    consumer(kotlinCompilation, result)
}
