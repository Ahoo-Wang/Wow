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
import type { ReactNode } from 'react';
import { App, ConfigProvider } from 'antd';

export function AntdProvider({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorErrorText: '#820014',
          colorLink: '#003a8c',
          colorPrimary: '#0958d9',
          colorSuccessText: '#135200',
          colorTextQuaternary: '#595959',
          colorTextSecondary: '#595959',
        },
      }}
    >
      <App className="story-antd">{children}</App>
    </ConfigProvider>
  );
}
