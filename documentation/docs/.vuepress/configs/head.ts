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

export const head: HeadConfig[] = [
    ['link', {rel: 'icon', href: `/favicon.ico`}],
    [
        'link',
        {
            rel: 'icon',
            type: 'image/png',
            sizes: '32x32',
            href: `/favicon.png`,
        },
    ],
    ['link', {rel: 'manifest', href: '/manifest.webmanifest'}],
    ['meta', {name: 'application-name', content: 'Wow'}],
    ['meta', {name: 'apple-mobile-web-app-title', content: 'Wow'}],
    ['meta', {name: 'apple-mobile-web-app-status-bar-style', content: 'black'}],
    [
        'link',
        {rel: 'apple-touch-icon', href: `/images/icons/apple-touch-icon.png`},
    ],
    [
        'link',
        {
            rel: 'mask-icon',
            href: '/images/icons/safari-pinned-tab.svg',
            color: '#3eaf7c',
        },
    ],
    ['meta', {name: 'msapplication-TileColor', content: '#3eaf7c'}],
    ['meta', {name: 'theme-color', content: '#3eaf7c'}],
]