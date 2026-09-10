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

import { useState } from 'react';
import {
  InputGroup,
  InputGroupInput,
  type RecordTableToolbarRenderContext,
  Button,
  FilterSelect,
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverTitle,
  ViewTheme,
  ViewPage,
  type ViewThemeProps,
} from '@ahoo-wang/fetcher-view-engine/react';
import '@ahoo-wang/fetcher-view-engine/styles.css';
import '@ahoo-wang/fetcher-view-engine/themes/neutral.css';
import '@ahoo-wang/fetcher-view-engine/themes/blue.css';
import '@ahoo-wang/fetcher-view-engine/themes/violet.css';
import '@ahoo-wang/fetcher-view-engine/themes/green.css';
import '@ahoo-wang/fetcher-view-engine/themes/orange.css';
import '@ahoo-wang/fetcher-view-engine/themes/shadcn.css';
import './my-theme.css';
import { createOrderService } from './orderService.js';
import { orderDefinition, orderViews } from './orders.js';
import { OrderOperationsProvider } from './OrderOperations.js';
import { orderExtensions } from './OrderExtensions.js';

/** All styling and components are consumed through public package entry points. */
export function ThemesExample() {
  const [theme, setTheme] = useState('blue');
  const [appearance, setAppearance] =
    useState<ViewThemeProps['appearance']>('light');
  const [density, setDensity] =
    useState<ViewThemeProps['density']>('comfortable');
  const [service] = useState(() => createOrderService());
  return (
    <div className="theme-example-host">
      <ViewTheme
        theme={theme}
        appearance={appearance}
        density={density}
        data-testid="active-theme"
        style={{ padding: 16 }}
      >
        <div
          style={{
            display: 'flex',
            gap: 12,
            flexWrap: 'wrap',
            marginBottom: 16,
          }}
        >
          <FilterSelect
            label="主题配色"
            value={theme}
            onValueChange={value => setTheme(value ?? 'neutral')}
            options={[
              'neutral',
              'blue',
              'violet',
              'green',
              'orange',
              'brand',
              'shadcn',
            ].map(value => ({ value, label: value }))}
          />
          <Button
            variant="outline"
            onClick={() =>
              setAppearance(appearance === 'dark' ? 'light' : 'dark')
            }
          >
            切换明暗
          </Button>
          <Button variant="outline" onClick={() => setAppearance('system')}>
            跟随系统
          </Button>
          <Button
            variant="outline"
            onClick={() =>
              setDensity(density === 'compact' ? 'comfortable' : 'compact')
            }
          >
            切换密度
          </Button>
          <Button data-testid="primary-color">主操作</Button>
          <ViewTheme
            data-testid="typography-scope"
            style={{
              '--fve-font-family': 'monospace',
              '--fve-font-size': '20px',
              '--fve-line-height': 2,
              '--fve-control-height': '3rem',
            }}
          >
            <Popover>
              <PopoverTrigger render={<Button variant="outline" />}>
                主题弹层
              </PopoverTrigger>
              <PopoverContent>
                <PopoverTitle>实时主题</PopoverTitle>
                <Button
                  data-testid="portal-color"
                  onClick={() => setTheme(theme === 'blue' ? 'violet' : 'blue')}
                >
                  切换弹层配色
                </Button>
                <Button
                  variant="outline"
                  onClick={() =>
                    setAppearance(appearance === 'dark' ? 'light' : 'dark')
                  }
                >
                  弹层切换明暗
                </Button>
                <input aria-label="保留输入" />
              </PopoverContent>
            </Popover>
          </ViewTheme>
        </div>
        <pre style={{ whiteSpace: 'pre-wrap' }}>
          {theme === 'brand'
            ? "import './my-theme.css';"
            : `import '@ahoo-wang/fetcher-view-engine/themes/${theme}.css';`}
          {'\n'}
          {`<ViewTheme theme="${theme}" appearance="${appearance}" density="${density}">`}
        </pre>
        <OrderOperationsProvider service={service}>
          {busy => (
            <ViewPage
              scopeKey="theme-example"
              definitionId={orderDefinition.id}
              definition={orderDefinition}
              instances={orderViews}
              host={service.host}
              extensions={orderExtensions}
              renderTableToolbar={context => <ToolbarNote {...context} />}
              selectable
              autoRefreshPaused={busy}
              initialSidebarCollapsed
            />
          )}
        </OrderOperationsProvider>
      </ViewTheme>
    </div>
  );
}

/** A real component owns Hooks; a render callback only composes it. */
function ToolbarNote({ defaultContent }: RecordTableToolbarRenderContext) {
  const [note, setNote] = useState('');
  return (
    <div>
      {defaultContent}
      <div style={{ padding: 8 }}>
        <InputGroup>
          <InputGroupInput
            aria-label="业务备注"
            placeholder="主题切换后保留业务输入"
            value={note}
            onChange={event => setNote(event.target.value)}
          />
        </InputGroup>
      </div>
    </div>
  );
}
