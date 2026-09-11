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

import type { Meta, StoryObj } from '@storybook/react-vite';
import { CompensationExample } from '../../packages/view-engine/examples/react/compensation/CompensationExample.js';
const meta = {
  id: 'view-engine-补偿-api-分析',
  title: 'View Engine/真实 API 接入/补偿分析',
  component: CompensationExample,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof CompensationExample>;
export default meta;
export const Dev: StoryObj<typeof meta> = { name: 'dev 服务联调' };
