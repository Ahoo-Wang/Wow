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

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import me.ahoo.wow.compiler.model.symbol.BoundedContextArguments
import me.ahoo.wow.manifest.ContextName

/**
 * The module's `@BoundedContext`s, as [ModelSymbolProcessor] collects them round by round: their names and
 * descriptions, those whose arguments KSP has not evaluated yet, and their files; and the context the module is
 * compiled for ([context]), the KSP option `wow.context` ([option]) else the module's one `@BoundedContext`. In generate
 * mode ([generate]), a context name that breaks the rule of one ([ContextName]) fails the compilation.
 */
internal class BoundedContexts(
    private val generate: Boolean,
    private val logger: KSPLogger,
    private val option: String?,
) {

    /**
     * The `@BoundedContext`s of the module: name to description. In generate mode, a name that breaks the rule of a
     * context name ([ContextName]), the annotation's or the KSP option's, fails the compilation.
     */
    private val boundedContexts = sortedMapOf<String, String>()

    /**
     * The declarations whose `@BoundedContext` has an argument KSP has not evaluated yet, by name: what the module
     * generates is named after its context, so nothing is generated in such a round, and one still unread when KSP
     * ends fails the compilation ([ModelSymbolProcessor.finish]).
     */
    private val unreadContexts = sortedSetOf<String>()

    /**
     * The files of the `@BoundedContext`s read, by declaration: every output named after the context lists them among
     * its sources, so KSP processes them again whenever it regenerates such an output.
     */
    private val contextFiles = linkedMapOf<String, KSFile>()

    /** The module's `@BoundedContext`s read: name to description. */
    val declared: Map<String, String> get() = boundedContexts

    /** The declarations whose `@BoundedContext` has an argument KSP has not evaluated yet, by name. */
    val unread: Set<String> get() = unreadContexts

    /** The files of the `@BoundedContext`s read, in the order they were read, a file once per declaration. */
    val files: Collection<KSFile> get() = contextFiles.values

    /** Whether the KSP option naming the context was checked against the rule of a context name, once per build. */
    private var optionChecked = false

    /** The bounded context of the module's types, when an option or a single `@BoundedContext` names it. */
    fun context(): String? = option ?: boundedContexts.keys.singleOrNull()

    /**
     * Collects the module's `@BoundedContext`s: the declarations returned are those with an argument KSP has not
     * evaluated yet, deferred to the next round, if KSP runs one ([unreadContexts]).
     */
    fun collect(resolver: Resolver): List<KSClassDeclaration> {
        // Every file of the module, not the round's alone: an incremental round hands the processor only the files KSP
        // found dirty, and the context names what each round generates, whichever file declares it.
        val declarations = resolver.getAllFiles().flatMap { it.declarations }.flatMap { it.classes() }
            .filter { declaration -> declaration.annotations.any(BoundedContextArguments::isBoundedContext) }
            .filterNot { declaration -> declaration.contextKey() in contextFiles }
        val unread = declarations.filter { declaration ->
            val name = declaration.contextKey()
            val read = declaration.annotations.filter(BoundedContextArguments::isBoundedContext)
                .map(BoundedContextArguments::of).toList()
            if (null in read) {
                unreadContexts += name
                return@filter true
            }
            unreadContexts -= name
            declaration.containingFile?.let {
                contextFiles[name] = it
            }
            read.filterNotNull().forEach { context ->
                boundedContexts[context.name] = context.description
                if (generate) {
                    ContextName.problem(context.name)?.let { logger.error("@BoundedContext: $it", declaration) }
                }
            }
            false
        }.toList()
        if (generate && !optionChecked) {
            optionChecked = true
            option?.let(ContextName::problem)?.let {
                logger.error("KSP option ${ModelSymbolProcessorProvider.CONTEXT_OPTION}: $it")
            }
        }
        return unread
    }

    private fun KSClassDeclaration.contextKey(): String = qualifiedName?.asString() ?: simpleName.asString()

    /** This declaration, when it is a class, and the classes nested in it. */
    private fun KSDeclaration.classes(): Sequence<KSClassDeclaration> =
        (this as? KSClassDeclaration)?.let { sequenceOf(it) + it.declarations.flatMap { nested -> nested.classes() } }
            .orEmpty()
}
