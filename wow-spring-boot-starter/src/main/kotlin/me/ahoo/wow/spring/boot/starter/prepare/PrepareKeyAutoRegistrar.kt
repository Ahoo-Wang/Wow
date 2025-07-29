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

package me.ahoo.wow.spring.boot.starter.prepare

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.PreparableKey
import me.ahoo.wow.infra.prepare.proxy.prepareKeyMetadata
import me.ahoo.wow.spring.prepare.PrepareKeyFactoryBean
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.filter.AnnotationTypeFilter

class PrepareKeyAutoRegistrar : ImportBeanDefinitionRegistrar, EnvironmentAware, BeanFactoryAware {
    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private lateinit var env: Environment
    private lateinit var beanFactory: BeanFactory
    override fun setEnvironment(environment: Environment) {
        this.env = environment
    }

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    private fun getBasePackages(): Set<String> {
        val applicationBasePackageScanner = beanFactory.getBean(ApplicationBasePackageScanner::class.java)
        return applicationBasePackageScanner.getStringSet(PrepareProperties.BASE_PACKAGES) + applicationBasePackageScanner.getApplicationBasePackages()
    }

    override fun registerBeanDefinitions(importingClassMetadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        val basePackages = getBasePackages()
        if (basePackages.isEmpty()) {
            logger.warn { "No auto-configuration base packages found" }
            return
        }
        val scanner = PreparableKeyScanner(false, env)
        for (basePackage in basePackages) {
            val candidates = scanner.findCandidateComponents(basePackage)
            for (candidate in candidates) {
                val beanClass = Class.forName(candidate.beanClassName)
                val prepareKeyMetadata = beanClass.kotlin.prepareKeyMetadata()
                if (registry.containsBeanDefinition(prepareKeyMetadata.name)) {
                    logger.info { "PrepareKey: ${prepareKeyMetadata.name} already exists" }
                    continue
                }
                val beanDefinitionBuilder =
                    BeanDefinitionBuilder.genericBeanDefinition(PrepareKeyFactoryBean::class.java)
                        .addConstructorArgValue(prepareKeyMetadata)
                registry.registerBeanDefinition(prepareKeyMetadata.name, beanDefinitionBuilder.beanDefinition)
                logger.info { "Register PrepareKey: ${prepareKeyMetadata.name}" }
            }
        }
    }

    class PreparableKeyScanner(useDefaultFilters: Boolean, environment: Environment) :
        ClassPathScanningCandidateComponentProvider(useDefaultFilters, environment) {
        init {
            addIncludeFilter(AnnotationTypeFilter(PreparableKey::class.java))
        }

        override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition): Boolean {
            return beanDefinition.metadata.isInterface
        }
    }
}
