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

package me.ahoo.wow.compiler.model

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File

/** Compiles [sources] with the model processor in [mode] (`generate`, `report` or `off`). */
@OptIn(ExperimentalCompilerApi::class)
fun compileModel(mode: String, vararg sources: SourceFile): Pair<KotlinCompilation, JvmCompilationResult> =
    compileModel(mode, emptyMap(), *sources)

/**
 * Compiles [sources], with the [NineXStubs], with the model processor in [mode] and the further KSP [options].
 */
@OptIn(ExperimentalCompilerApi::class)
fun compileModel(
    mode: String,
    options: Map<String, String>,
    vararg sources: SourceFile,
): Pair<KotlinCompilation, JvmCompilationResult> = compileModel(mode, options, null, *sources)

/**
 * Compiles [sources] as a library of their own, without the model processor, and gives the directory of its classes:
 * a jar a domain module depends on, whose declarations the processor reads from the class path, not from sources.
 */
@OptIn(ExperimentalCompilerApi::class)
fun compileLibrary(vararg sources: SourceFile): File {
    val compilation = KotlinCompilation().apply {
        inheritClassPath = true
        this.sources = sources.toList()
        jvmTarget = "21"
    }
    val result = compilation.compile()
    check(result.exitCode == KotlinCompilation.ExitCode.OK) { result.messages }
    return result.outputDirectory
}

/** Compiles [sources] as [compileModel] does, with the [libraries] (directories of classes) on the class path. */
@OptIn(ExperimentalCompilerApi::class)
fun compileModelAgainst(
    mode: String,
    libraries: List<File>,
    vararg sources: SourceFile,
): Pair<KotlinCompilation, JvmCompilationResult> = compileModel(mode, emptyMap(), null, libraries, *sources)

/**
 * Compiles [sources] as [compileModel] does; when [onClasspath] is given, against only the test's class path entries
 * it keeps: a module without some dependency.
 */
@OptIn(ExperimentalCompilerApi::class)
fun compileModel(
    mode: String,
    options: Map<String, String>,
    onClasspath: ((File) -> Boolean)?,
    vararg sources: SourceFile,
): Pair<KotlinCompilation, JvmCompilationResult> = compileModel(mode, options, onClasspath, emptyList(), *sources)

@OptIn(ExperimentalCompilerApi::class)
private fun compileModel(
    mode: String,
    options: Map<String, String>,
    onClasspath: ((File) -> Boolean)?,
    libraries: List<File>,
    vararg sources: SourceFile,
): Pair<KotlinCompilation, JvmCompilationResult> {
    val compilation = KotlinCompilation().apply {
        inheritClassPath = onClasspath == null
        if (onClasspath != null) {
            classpaths = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).filter(onClasspath)
        }
        classpaths = classpaths + libraries
        this.sources = sources.toList() + NineXStubs.SOURCES
        configureKsp {
            symbolProcessorProviders += ModelSymbolProcessorProvider()
            processorOptions[ModelSymbolProcessorProvider.OPTION] = mode
            processorOptions.putAll(options)
        }
        jvmTarget = "21"
    }
    return compilation to compilation.compile()
}
