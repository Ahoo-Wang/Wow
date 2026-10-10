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

package me.ahoo.wow.spring.boot.starter

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.core.type.classreading.MetadataReaderFactory

/**
 * Leaves the classes nested in Wow's auto-configurations out of a host's component scan.
 *
 * `@SpringBootApplication` excludes the auto-configurations themselves from its scan (`AutoConfigurationExcludeFilter`)
 * but not their nested `@Configuration` classes. Scanned, such a class is registered before every auto-configuration
 * and without its outer class's conditions. The scan also applies the [TypeExcludeFilter] beans, this one among them,
 * so where it covers a Wow starter's package (Wow's own tests and servers, under `me.ahoo.wow`) the nested classes are
 * registered only through their auto-configuration, as in any other host.
 */
internal class NestedAutoConfigurationExcludeFilter : TypeExcludeFilter() {
    override fun match(metadataReader: MetadataReader, metadataReaderFactory: MetadataReaderFactory): Boolean {
        var classMetadata = metadataReader.classMetadata
        while (classMetadata.hasEnclosingClass()) {
            val enclosingClassName = requireNotNull(classMetadata.enclosingClassName)
            val enclosing = metadataReaderFactory.getMetadataReader(enclosingClassName)
            if (enclosingClassName.startsWith(WOW_PACKAGE_PREFIX) &&
                enclosing.annotationMetadata.isAnnotated(AutoConfiguration::class.java.name)
            ) {
                return true
            }
            classMetadata = enclosing.classMetadata
        }
        return false
    }

    override fun equals(other: Any?): Boolean = other is NestedAutoConfigurationExcludeFilter

    override fun hashCode(): Int = NestedAutoConfigurationExcludeFilter::class.hashCode()

    companion object {
        const val BEAN_NAME = "wowNestedAutoConfigurationExcludeFilter"
        private const val WOW_PACKAGE_PREFIX = "me.ahoo.wow."
    }
}

/** Registers [NestedAutoConfigurationExcludeFilter] before the context refreshes, and so before its component scan. */
internal class NestedAutoConfigurationExcludeFilterInitializer :
    ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val beanFactory = applicationContext.beanFactory
        if (!beanFactory.containsSingleton(NestedAutoConfigurationExcludeFilter.BEAN_NAME)) {
            beanFactory.registerSingleton(
                NestedAutoConfigurationExcludeFilter.BEAN_NAME,
                NestedAutoConfigurationExcludeFilter(),
            )
        }
    }
}
