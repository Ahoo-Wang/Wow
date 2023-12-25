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

import {defaultTheme, defineUserConfig} from 'vuepress'
import {searchPlugin} from '@vuepress/plugin-search'
import {head, navbarZh, sidebarZh} from "./configs";

const siteBase: `/${string}/` = process.env.SITE_BASE || "/"

export default defineUserConfig({
    base: siteBase,
    lang: 'zh-CN',
    title: '领域模型即服务 | Wow',
    description: '领域模型即服务 | Modern Reactive CQRS Architecture Microservice development framework based on DDD and EventSourcing. | 基于 DDD、EventSourcing 的现代响应式 CQRS 架构微服务开发框架',
    head: head,
    theme: defaultTheme({
            repo: 'Ahoo-Wang/Wow',
            docsBranch: 'main',
            docsDir: "documentation/docs",
            editLink: true,
            lastUpdatedText: '上次更新',
            logo: '/images/logo.svg',
            navbar: navbarZh,
            sidebar: sidebarZh
        }
    ),
    plugins: [searchPlugin()],
    port: 8555
})