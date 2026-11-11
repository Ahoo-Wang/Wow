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

package me.ahoo.wow.spring.boot.node

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.manifest.ManifestLoader
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.WowProperties
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.ClassUtils

/**
 * What the node serves: the models of the contexts it is given and their factories' parameters ([WowModels],
 * [Dependencies]), the Manifest merged from the modules' fragments ([ManifestLoader]), and with it the peer contracts
 * on the classpath ([ContractDirectory]). Imported by [WowAutoConfiguration], whose order and conditions it shares.
 */
@Configuration(proxyBeanMethods = false)
internal class NodeModels {
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowDependencies(beanFactory: ConfigurableListableBeanFactory): Dependencies =
        BeanFactoryDependencies(beanFactory)

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowModels(
        properties: WowProperties,
        beanFactory: ConfigurableListableBeanFactory,
        dependencies: Dependencies,
    ): WowModels {
        val providers = properties.contexts.selection().select(ModelProvider.load(beanFactory.beanClassLoader))
        return WowModels(providers, ServiceNode.models(dependencies, providers))
    }

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowManifestLoader(beanFactory: ConfigurableListableBeanFactory): ManifestLoader =
        ManifestLoader(beanFactory.beanClassLoader ?: ClassUtils.getDefaultClassLoader()!!, WowJson.mapper)

    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowManifest(loader: ManifestLoader, models: WowModels, identity: NodeIdentity): Manifest =
        loader.load(ServiceInfo(identity.service), models.providers)

    /** The contracts of the service's Manifest and the peer contracts on the class path. */
    @Bean
    @ConditionalOnMissingBean
    @InternalWowApi
    fun wowContractDirectory(manifest: Manifest, beanFactory: ConfigurableListableBeanFactory): ContractDirectory =
        ContractDirectory.load(
            manifest,
            beanFactory.beanClassLoader ?: ClassUtils.getDefaultClassLoader()!!,
            WowJson.mapper
        )

    internal companion object {
        /** The service of the peer [context], as its contract on the class path names it. */
        internal fun serviceOf(contracts: ContractDirectory, context: String): String? = contracts.peers
            .firstOrNull { fragment -> fragment.manifest.contexts.any { it.name == context } }
            ?.manifest?.service?.name?.takeIf { it.isNotBlank() }
    }
}
