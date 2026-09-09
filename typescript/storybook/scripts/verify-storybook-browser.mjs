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
const require = createRequire(import.meta.url);
const { chromium } = createRequire(
  require.resolve('@vitest/browser-playwright'),
)('playwright');
const origin = process.argv[2] ?? 'http://127.0.0.1:6006';
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const index = await (await fetch(`${origin}/index.json`)).json();
    const entries = Object.values(index.entries);
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1000 },
    });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    async function open(id, mode = 'story') {
      await page.goto(
        `${origin}/iframe.html?id=${encodeURIComponent(id)}&viewMode=${mode}`,
        { waitUntil: 'domcontentloaded' },
      );
      assert.equal(await page.locator('#error-message').isVisible(), false, id);
    }
    await open('overview--start-here');
    await page.getByRole('heading', { name: '从示例开始接入' }).waitFor();
    for (const href of await page
      .locator('a[href^="./?path="]')
      .evaluateAll(a => a.map(x => x.getAttribute('href')))) {
      const route = new URL(href, origin).searchParams.get('path');
      assert(index.entries[route.replace(/^\/(docs|story)\//, '')], href);
    }
    const business = entries.find(
      e =>
        e.type === 'story' &&
        e.importPath.endsWith('/Querying.stories.tsx') &&
        e.exportName === 'BusinessRecords',
    );
    await open(business.id);
    await page.getByText('ORD-202609-1001', { exact: true }).waitFor();
    for (const field of ['save', 'create', 'rename', 'delete'])
      assert.equal(
        await page.getByTestId('record-' + field + '-count').textContent(),
        '0',
      );
    assert.equal(
      await page.getByTestId('record-query-count').textContent(),
      '1',
    );
    // Public render remains unchanged after the interactions that formerly ran automatically.
    await page.waitForTimeout(1800);
    for (const field of ['save', 'create', 'rename', 'delete'])
      assert.equal(
        await page.getByTestId('record-' + field + '-count').textContent(),
        '0',
      );
    assert.equal(
      await page.getByTestId('record-query-count').textContent(),
      '1',
    );
    await page.setViewportSize({ width: 414, height: 896 });
    await open('overview--start-here');
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    );
    await page.setViewportSize({ width: 1440, height: 1000 });
    for (const doc of entries.filter(entry => entry.type === 'docs')) {
      const title = doc.title;
      assert(doc, title);
      await open(doc.id, 'docs');
      await page.locator('.sbdocs-content').waitFor();
      await page.getByRole('navigation', { name: '独立场景' }).waitFor();
      if (title === 'HTTP/Fetcher') {
        const iframes = page.locator('iframe[src*="viewMode=story"]');
        await iframes.first().waitFor();
        assert.equal(
          await iframes.count(),
          1,
          'Docs renders only one primary iframe',
        );
        const first = page
          .frameLocator('iframe[src*="viewMode=story"]')
          .first();
        await first.getByRole('button', { name: 'Send request' }).click();
        await first.getByText('Ada', { exact: true }).waitFor();
        const other = await browser.newPage();
        await other.goto(
          `${origin}/iframe.html?id=http-fetcher--path-and-query&viewMode=story`,
        );
        await other.getByText('Ready', { exact: true }).waitFor();
        await other.getByRole('button', { name: 'Send request' }).click();
        await other
          .getByText('GET https://api.example.test/users/u-ada?include=team', {
            exact: true,
          })
          .waitFor();
        assert.equal(await first.getByText('Ada', { exact: true }).count(), 1);
        await other.close();
      }
      if (title === 'View Engine/Record View/表格与汇总') {
        await page.getByText('ORD-202609-1001', { exact: true }).waitFor();
      }
      console.log('Docs verified:', title);
    }
    const dark = entries.find(
      e =>
        e.importPath.endsWith('/QuickStart.stories.tsx') &&
        e.exportName === 'NarrowDark',
    );
    await open(dark.id);
    await page.getByText('DEMO-1', { exact: true }).waitFor();
    assert.equal(
      (await page.locator('.fve-root[data-theme=dark]').count()) > 0,
      true,
    );
    const builtins = entries.find(
      e =>
        e.importPath.endsWith('/BuiltinFilters.stories.tsx') &&
        e.exportName === 'BrowserStorage',
    );
    assert.ok(builtins, 'Built-in persistence example missing');
    await open(builtins.id);
    await page.getByRole('combobox', { name: '参与人', exact: true }).waitFor();
    await page.getByRole('combobox', { name: '参与人', exact: true }).click();
    await page.getByRole('button', { name: '加载更多', exact: true }).click();
    await page.getByRole('option', { name: '用户丙', exact: true }).click();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.waitForFunction(() =>
      Array.from(document.querySelectorAll('button')).some(
        button => button.textContent?.trim() === '保存' && button.disabled,
      ),
    );
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() =>
      document
        .querySelector('[aria-label="参与人"]')
        ?.textContent?.includes('+1'),
    );
    assert.equal(
      await page.getByTestId('builtin-query-count').textContent(),
      '1',
    );
    await page
      .getByRole('button', { name: '查看保存 JSON', exact: true })
      .click();
    await page.waitForFunction(() =>
      Boolean(
        document.querySelector('[data-testid="builtin-saved"]')?.textContent,
      ),
    );
    const persisted = JSON.parse(
      await page.getByTestId('builtin-saved').textContent(),
    );
    const multi = persisted.config.filters.root.operands.find(
      node => node.component.name === 'remote-multi-select',
    );
    assert.deepEqual(multi.props.values, ['u1', 'u3']);
    assert.deepEqual(
      multi.props.selectedOptions.map(item => item.label),
      ['保存的用户甲', '用户丙'],
    );
    console.log(
      'Built-in remote selection: cross-page values and labels survive browser reload',
    );
    const cells = entries.find(
      e =>
        e.importPath.endsWith('/BuiltinCells.stories.tsx') &&
        e.exportName === 'BrowserStorage',
    );
    assert.ok(cells, 'Built-in cell persistence example missing');
    await open(cells.id);
    await page.getByText('ORDER-20260908-000001', { exact: true }).waitFor();
    await page.getByRole('button', { name: '列设置', exact: true }).click();
    await page.getByRole('checkbox', { name: /显示.*链接/ }).uncheck();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.waitForFunction(() =>
      Array.from(document.querySelectorAll('button')).some(
        button => button.textContent?.trim() === '保存' && button.disabled,
      ),
    );
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByText('ORDER-20260908-000001', { exact: true }).waitFor();
    assert.equal(
      await page
        .getByRole('columnheader', { name: '链接', exact: true })
        .count(),
      0,
    );
    assert.equal(await page.getByText('¥1,234.50', { exact: true }).count(), 1);
    await page
      .getByRole('button', { name: '查看保存 JSON', exact: true })
      .click();
    await page.waitForFunction(() =>
      Boolean(
        document.querySelector('[data-testid="builtin-cells-saved"]')
          ?.textContent,
      ),
    );
    const cellConfig = JSON.parse(
      await page.getByTestId('builtin-cells-saved').textContent(),
    ).config.presentation.table.columns;
    assert.deepEqual(cellConfig.find(column => column.id === 'id').renderer, {
      name: 'text',
      options: { ellipsis: true, copyable: true },
    });
    assert.equal(
      cellConfig.find(column => column.id === 'link').visible,
      false,
    );
    console.log(
      'Built-in cells: renderer options and column visibility survive browser reload',
    );
    assert.deepEqual(errors, []);
    console.log(
      'Navigation, passive initial state, iframe isolation, narrow layout, dark mode: passed',
    );
  } finally {
    await browser.close();
  }
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
