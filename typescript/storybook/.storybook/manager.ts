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

import { addons } from 'storybook/manager-api';
import { create } from 'storybook/theming';

addons.setConfig({
  theme: create({
    base: 'dark',
    brandTitle: 'Fetcher Scenario Lab',
    brandUrl: './?path=/docs/overview--docs',
    brandTarget: '_self',
    colorPrimary: '#6d5dfc',
    colorSecondary: '#0958d9',
    appBg: '#111318',
    appBorderColor: '#303641',
    appBorderRadius: 8,
    barBg: '#171a20',
    barTextColor: '#b7c0cc',
    barSelectedColor: '#fff',
    barHoverColor: '#fff',
    inputBg: '#20242b',
    inputBorder: '#353b45',
    inputTextColor: '#f5f7fa',
    inputBorderRadius: 8,
  }),
});
