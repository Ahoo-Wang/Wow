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
import { HttpOrderExample } from '../../../packages/view-engine/dev/HttpOrderExample.js';

const meta = {
  title: '开发验证/HTTP 视图服务实验',
  id: 'development-http-service',
  component: HttpOrderExample,
  tags: ['!autodocs', '!test'],
  parameters: {
    layout: 'fullscreen',
    docs: {
      description: {
        component:
          '需要独立启动本地 HTTP 视图服务的开发实验；不进入普通文档与默认测试。',
      },
    },
  },
} satisfies Meta<typeof HttpOrderExample>;

export default meta;

export const HttpViewService: StoryObj<typeof HttpOrderExample> = {
  name: '开发实验 · HTTP 视图服务',
  tags: ['!autodocs', '!test'],
  args: {
    scopeKey: 'tenant:alice',
    accessToken: 'alice-token',
    viewServiceTimeoutMs: 1000,
    initialSidebarCollapsed: false,
  },
  render: args => (
    <HttpOrderExample
      {...args}
      viewServiceUrl={
        new URLSearchParams(location.search).get('viewService') ??
        'http://127.0.0.1:6010/view-service/'
      }
    />
  ),
  parameters: {
    docs: {
      description: {
        story:
          '运行 verify-http-view-host.mjs --serve 启动测试服务。所有视图读写通过 HTTP；五类组件扩展留在前端。',
      },
    },
  },
};
