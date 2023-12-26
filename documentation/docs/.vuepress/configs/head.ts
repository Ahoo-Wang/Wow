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

import type {HeadConfig} from '@vuepress/core'
import {SITE_BASE} from "./SITE_BASE";

export const head: HeadConfig[] = [
    ['link', {rel: 'icon', href: `${SITE_BASE}favicon.ico`}],
    ['link', {rel: 'manifest', href: `${SITE_BASE}manifest.webmanifest`}],
    ['meta', {name: 'application-name', content: 'Wow'}],
    ['meta', {name: 'apple-mobile-web-app-title', content: 'Wow'}],
    ['meta', {name: 'apple-mobile-web-app-status-bar-style', content: 'black'}],
    ['meta', {name: 'msapplication-TileColor', content: '#3eaf7c'}],
    ['meta', {name: 'theme-color', content: '#3eaf7c'}],
]