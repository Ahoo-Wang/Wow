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

package me.ahoo.wow.compensation.server.webhook.weixin

import me.ahoo.coapi.spring.boot.starter.CoApiAutoConfiguration
import me.ahoo.test.asserts.assert
import me.ahoo.wow.compensation.server.CompensationServer
import me.ahoo.wow.compensation.server.configuration.CompensationProperties
import me.ahoo.wow.compensation.server.webhook.weixin.client.WeiXinBotApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration
import org.springframework.context.annotation.ComponentScan

class WeiXinWebHookConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(CompensationServerPackage::class.java)
        .withConfiguration(
            AutoConfigurations.of(WebClientAutoConfiguration::class.java, CoApiAutoConfiguration::class.java)
        )

    @Test
    fun `should start without weixin webhook url`() {
        contextRunner.run(::assertWeiXinWebHookDisabled)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "false", "FALSE"])
    fun `should start with weixin webhook disabled by url value`(url: String) {
        contextRunner
            .withPropertyValues("${WeiXinWebHookProperties.URL_KEY}=$url")
            .run(::assertWeiXinWebHookDisabled)
    }

    @Test
    fun `should register weixin webhook when url is configured`() {
        contextRunner
            .withPropertyValues(
                "${WeiXinWebHookProperties.URL_KEY}=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test"
            )
            .run { context ->
                context.assert()
                    .hasSingleBean(WeiXinBotApi::class.java)
                    .hasSingleBean(WeiXinWebHook::class.java)
            }
    }

    private fun assertWeiXinWebHookDisabled(context: AssertableApplicationContext) {
        context.startupFailure.assert().isNull()
        context.assert()
            .doesNotHaveBean(WeiXinBotApi::class.java)
            .doesNotHaveBean(WeiXinWebHook::class.java)
            .doesNotHaveBean(WeiXinWebHookProperties::class.java)
    }

    /**
     * Mirrors [CompensationServer]: CoApi scans its package, and component scanning registers the WeCom beans.
     */
    @AutoConfigurationPackage(basePackageClasses = [CompensationServer::class])
    @ComponentScan(basePackageClasses = [WeiXinWebHookConfiguration::class])
    @EnableConfigurationProperties(CompensationProperties::class)
    class CompensationServerPackage
}
