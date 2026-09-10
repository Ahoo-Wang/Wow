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
import { ProductCatalogExample } from '../../packages/view-engine/examples/react/catalog/ProductCatalogExample.js';
import { RecordViewExample } from '../docs/RecordViewExample.js';
const meta = {
  id: 'view-engine-专项场景-卡片模式',
  title: 'View Engine/专项场景/数据展示/卡片模式',
  component: ProductCatalogExample,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof ProductCatalogExample>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Cards: Story = { name: '商品选品库 · 封面与快捷操作' };
export const Configuration: Story = {
  name: '基础配置与往返',
  render: args => (
    <RecordViewExample appearance={args.appearance} layout="card" />
  ),
};
export const NarrowDark: Story = {
  name: '窄屏深色',
  args: { appearance: 'dark' },
  render: args => (
    <div style={{ maxWidth: 375 }}>
      <ProductCatalogExample {...args} />
    </div>
  ),
};
export const CustomContent: Story = {
  name: '自定义商品卡片',
  args: { custom: true },
};
export const Persisted: Story = {
  name: '保存商品视图并重载',
  args: { persistViews: true },
};
