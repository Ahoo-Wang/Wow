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

package me.ahoo.wow.compiler.query

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import me.ahoo.wow.compiler.AggregateRootResolver.AGGREGATE_ROOT_NAME
import me.ahoo.wow.compiler.query.StateAggregateRootResolver.resolveStateAggregateRoot

class QuerySymbolProcessor(environment: SymbolProcessorEnvironment) :
    SymbolProcessor {

    private val logger = environment.logger
    private val codeGenerator = environment.codeGenerator
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("QuerySymbolProcessor - process[$this]")
        resolver.getSymbolsWithAnnotation(AGGREGATE_ROOT_NAME)
            .filterIsInstance<KSClassDeclaration>()
            .filter {
                it.validate()
            }
            .forEach {
                it.resolveStateAggregateRoot().writeFile(codeGenerator)
            }

        return emptyList()
    }

    override fun finish() {
        logger.info("QuerySymbolProcessor - finish[$this]")
    }

    override fun onError() {
        logger.info("QuerySymbolProcessor - onError[$this]")
    }
}
