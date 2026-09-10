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
import { ThemesExample } from '../../packages/view-engine/examples/react/ThemesExample.js';
const meta = {
  id: 'view-engine-专项场景-主题-可导入主题',
  title: 'View Engine/专项场景/组件与主题/可导入主题',
  component: ThemesExample,
} satisfies Meta<typeof ThemesExample>;
export default meta;
type Story = StoryObj<typeof meta>;
export const ImportableThemes: Story = { name: '内置、自定义与宿主主题' };
