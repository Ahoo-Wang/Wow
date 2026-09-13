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
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { join } from 'node:path';
const require = createRequire(
  new URL('../../../package.json', import.meta.url),
);
const { chromium } = createRequire(
  require.resolve('@vitest/browser-playwright'),
)('playwright');
const origin = process.env.VIEW_ENGINE_E2E_BASE_URL ?? 'http://127.0.0.1:6006';
const channel = process.env.VIEW_ENGINE_BROWSER_CHANNEL;
const browser = await chromium.launch({
  ...(channel ? { channel } : {}),
  headless: true,
});
try {
  const degraded = await browser.newPage({
    viewport: { width: 1440, height: 1000 },
  });
  const degradedErrors = [];
  degraded.on('pageerror', error => degradedErrors.push(error.message));
  let blockedLayouts = 0;
  await degraded.route(/\/DashboardGrid[^/]*\.js(?:\?.*)?$/, route => {
    blockedLayouts++;
    return route.abort();
  });
  await degraded.goto(
    `${origin}/iframe.html?id=view-engine-dashboard--overview&viewMode=story`,
    { waitUntil: 'domcontentloaded' },
  );
  await degraded.getByText(/已切换为简洁布局/).waitFor();
  assert.ok(
    blockedLayouts > 0,
    'The production layout chunk must actually be blocked',
  );
  assert.equal(
    await degraded.getByRole('main', { name: '视图工作区' }).isVisible(),
    true,
  );
  await degraded
    .getByRole('cell', { name: 'SO-1', exact: true })
    .first()
    .waitFor();
  assert.equal(
    await degraded
      .getByRole('button', { name: '编辑布局', exact: true })
      .isDisabled(),
    true,
  );
  await degraded.getByRole('button', { name: '添加', exact: true }).click();
  await degraded
    .getByRole('menuitem', { name: '添加Markdown', exact: true })
    .click();
  await degraded
    .getByRole('textbox', { name: '标题', exact: true })
    .fill('Layout recovery note');
  await degraded
    .getByRole('textbox', { name: 'Markdown 内容', exact: true })
    .fill('Draft preserved while layout is unavailable.');
  await degraded.getByRole('button', { name: '添加内容', exact: true }).click();
  await degraded
    .getByRole('heading', { name: 'Layout recovery note' })
    .waitFor();
  assert.equal(
    await degraded
      .getByRole('button', { name: '保存', exact: true })
      .isEnabled(),
    true,
  );
  await degraded.getByRole('button', { name: '重试布局', exact: true }).click();
  await degraded.getByText(/已切换为简洁布局/).waitFor();
  await degraded
    .getByRole('heading', { name: 'Layout recovery note' })
    .waitFor();
  await degraded.getByRole('button', { name: '保存', exact: true }).click();
  await degraded
    .getByRole('status', { name: '保存状态' })
    .filter({ hasText: '视图已保存' })
    .waitFor();
  assert.deepEqual(
    degradedErrors,
    [],
    'Layout failure must not escape the boundary',
  );
  if (process.env.VIEW_ENGINE_ARTIFACTS) {
    await degraded.evaluate(() => window.scrollTo(0, 0));
    await degraded.screenshot({
      path: join(
        process.env.VIEW_ENGINE_ARTIFACTS,
        'dashboard-layout-fallback.png',
      ),
    });
  }
  await degraded.close();
  console.log(
    'Dashboard layout chunk failure: navigation, data, draft edits, retry and save remain usable',
  );
} finally {
  await browser.close();
}
