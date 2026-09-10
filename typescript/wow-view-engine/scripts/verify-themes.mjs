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

// Validate real computed CSS against the built package, without a host Tailwind build.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ViewTheme, Button } from '../dist/react.js';
const base = await readFile(
  new URL('../dist/styles.css', import.meta.url),
  'utf8',
);
const themes = ['neutral', 'blue', 'violet', 'green', 'orange', 'shadcn'];
const sheets = await Promise.all(
  themes.map(name =>
    readFile(new URL(`../dist/themes/${name}.css`, import.meta.url), 'utf8'),
  ),
);
const browser = await chromium.launch({
  headless: true,
  ...(process.env.VIEW_ENGINE_BROWSER_CHANNEL
    ? { channel: process.env.VIEW_ENGINE_BROWSER_CHANNEL }
    : {}),
});
try {
  const page = await browser.newPage();
  await page.setContent(
    `<style>${base}\n${sheets.join('\n')}</style><div class="fve-root" id="scope" data-fve-theme="blue" data-theme="light"><button id="button" class="fve:bg-primary fve:text-primary-foreground fve:rounded-lg fve:text-sm">Button</button><div class="fve-root" id="inner" data-fve-theme="brand"><button id="nested" class="fve:rounded-lg fve:text-sm">Nested</button></div></div>`,
  );
  await page.evaluate(() => {
    const host = document.createElement('div');
    host.className = 'fve-root';
    host.style.cssText = 'font-family:monospace;font-size:22px;line-height:2';
    host.innerHTML =
      '<span class="fve-root" id="inherited-font">Inherited</span>';
    document.body.append(host);
  });
  assert.deepEqual(
    await page.locator('#inherited-font').evaluate(el => {
      const css = getComputedStyle(el);
      return [css.fontFamily, css.fontSize, css.lineHeight];
    }),
    ['monospace', '22px', '44px'],
    'internal wrappers inherit host typography',
  );
  const typographyMarkup = renderToStaticMarkup(
    createElement(
      ViewTheme,
      { theme: 'blue' },
      createElement(
        ViewTheme,
        {
          style: {
            '--fve-font-family': 'monospace',
            '--fve-font-size': '20px',
            '--fve-line-height': 2,
          },
        },
        createElement('span', { id: 'anonymous-text' }, 'Plain'),
        createElement(Button, { id: 'anonymous-control' }, 'Control'),
      ),
      createElement(
        ViewTheme,
        { style: { fontFamily: 'serif', fontSize: '24px', lineHeight: 2 } },
        createElement(
          ViewTheme,
          { style: { '--fve-font-family': 'monospace' } },
          createElement('span', { id: 'partial-font' }, 'Partial'),
        ),
      ),
    ),
  );
  await page
    .locator('body')
    .evaluate(
      (body, markup) => body.insertAdjacentHTML('beforeend', markup),
      typographyMarkup,
    );
  for (const id of ['anonymous-text', 'anonymous-control']) {
    assert.deepEqual(
      await page.locator(`#${id}`).evaluate(el => {
        const css = getComputedStyle(el);
        return [css.fontFamily, css.fontSize, css.lineHeight];
      }),
      ['monospace', '20px', '40px'],
      'anonymous ViewTheme applies inline typography tokens',
    );
  }
  assert.deepEqual(
    await page.locator('#partial-font').evaluate(el => {
      const css = getComputedStyle(el);
      return [css.fontFamily, css.fontSize, css.lineHeight];
    }),
    ['monospace', '24px', '48px'],
    'partial typography does not reset inherited dimensions',
  );
  const color = () =>
    page
      .locator('#button')
      .evaluate(el => getComputedStyle(el).backgroundColor);
  const blue = await color();
  await page
    .locator('#scope')
    .evaluate(el => (el.dataset.fveTheme = 'neutral'));
  assert.notEqual(
    await color(),
    blue,
    'selected theme changes actual button color',
  );
  await page.addStyleTag({
    content: `.fve-root[data-fve-theme=brand]{--fve-radius:0px;--fve-font-size:20px}`,
  });
  assert.equal(
    await page
      .locator('#nested')
      .evaluate(el => getComputedStyle(el).borderRadius),
    '0px',
  );
  assert.equal(
    await page.locator('#nested').evaluate(el => getComputedStyle(el).fontSize),
    '20px',
  );
  await page.locator('#scope').evaluate(el => {
    el.dataset.fveTheme = 'blue';
    el.dataset.theme = 'dark';
  });
  assert.notEqual(await color(), blue, 'dark palette changes effective color');
  await page.addStyleTag({ content: sheets.slice().reverse().join('\n') });
  const dark = await color();
  await page.addStyleTag({ content: sheets.join('\n') });
  assert.equal(await color(), dark, 'theme import order is irrelevant');
  // Check readable primary/foreground pairs using opaque sRGB primary/foreground pixels.
  for (const name of themes.filter(name => name !== 'shadcn')) {
    for (const appearance of ['light', 'dark']) {
      await page.locator('#scope').evaluate(
        (el, values) => {
          el.dataset.fveTheme = values[0];
          el.dataset.theme = values[1];
        },
        [name, appearance],
      );
      const contrast = await page.locator('#button').evaluate(el => {
        const css = getComputedStyle(el),
          canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const ctx = canvas.getContext('2d');
        const luminance = color => {
          ctx.clearRect(0, 0, 1, 1);
          ctx.fillStyle = color;
          ctx.fillRect(0, 0, 1, 1);
          const rgb = Array.from(ctx.getImageData(0, 0, 1, 1).data)
            .slice(0, 3)
            .map(v => {
              v /= 255;
              return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
            });
          return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
        };
        const a = luminance(css.backgroundColor),
          b = luminance(css.color);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      });
      assert.ok(
        contrast >= 4.5,
        `${name}/${appearance} primary text contrast ${contrast}`,
      );
    }
  }
  // Focus palette contrast against the actual scope surface, after CSS color resolution.
  await page.locator('#scope').evaluate(el => {
    const probe = document.createElement('i');
    probe.id = 'focus-probe';
    probe.style.backgroundColor = 'var(--fve-ring)';
    el.append(probe);
    el.style.backgroundColor = 'var(--fve-background)';
  });
  for (const name of themes.filter(name => name !== 'shadcn'))
    for (const appearance of ['light', 'dark']) {
      await page.locator('#scope').evaluate(
        (el, values) => {
          el.dataset.fveTheme = values[0];
          el.dataset.theme = values[1];
        },
        [name, appearance],
      );
      const contrast = await page.evaluate(() => {
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const ctx = canvas.getContext('2d');
        const background = getComputedStyle(
          document.querySelector('#scope'),
        ).backgroundColor;
        function luminance(color) {
          ctx.clearRect(0, 0, 1, 1);
          ctx.fillStyle = background;
          ctx.fillRect(0, 0, 1, 1);
          ctx.fillStyle = color;
          ctx.fillRect(0, 0, 1, 1);
          const [r, g, b] = Array.from(ctx.getImageData(0, 0, 1, 1).data)
            .slice(0, 3)
            .map(v => {
              v /= 255;
              return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
            });
          return 0.2126 * r + 0.7152 * g + 0.0722 * b;
        }
        const a = luminance(background),
          b = luminance(
            getComputedStyle(document.querySelector('#focus-probe'))
              .backgroundColor,
          );
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
      });
      assert.ok(
        contrast >= 3,
        `${name}/${appearance} focus palette contrast ${contrast}`,
      );
    }
  if (process.env.VIEW_ENGINE_THEME_URL) {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(process.env.VIEW_ENGINE_THEME_URL);
    await page.getByTestId('primary-color').waitFor();
    const primary = () =>
      page
        .getByTestId('primary-color')
        .evaluate(el => getComputedStyle(el).backgroundColor);
    const chooseTheme = async name => {
      await page
        .getByRole('combobox', { name: '主题配色', exact: true })
        .click();
      await page.getByRole('option', { name, exact: true }).click();
    };
    await chooseTheme('brand');
    await page.waitForFunction(
      () =>
        getComputedStyle(
          document.querySelector('[data-testid="primary-color"]'),
        ).backgroundColor === 'rgb(29, 78, 216)',
    );
    assert.equal(
      await page
        .getByTestId('primary-color')
        .evaluate(el => getComputedStyle(el).borderRadius),
      '4px',
    );
    await chooseTheme('shadcn');
    await page.waitForFunction(
      () =>
        getComputedStyle(
          document.querySelector('[data-testid="primary-color"]'),
        ).backgroundColor === 'rgb(109, 40, 217)',
    );
    await chooseTheme('blue');
    await page.waitForFunction(() =>
      getComputedStyle(
        document.querySelector('[data-testid="primary-color"]'),
      ).backgroundColor.startsWith('oklch('),
    );
    const selected = page.getByRole('checkbox', {
      name: '选择记录 DEMO-1',
      exact: true,
    });
    await selected.check();
    await page.getByTestId('active-theme').evaluate(el => {
      el.style.fontFamily = 'monospace';
    });
    assert.equal(
      await page
        .getByTestId('primary-color')
        .evaluate(el => getComputedStyle(el).fontFamily),
      'monospace',
    );
    const initial = await primary();
    const note = page.getByRole('textbox', { name: '业务备注' });
    await note.fill('retained business note');
    await page.getByRole('button', { name: '主题弹层', exact: true }).click();
    const popup = page.getByRole('dialog', { name: '实时主题' });
    await popup.waitFor();
    for (const element of [
      page.getByTestId('typography-scope'),
      popup,
      page.getByTestId('portal-color'),
    ]) {
      assert.deepEqual(
        await element.evaluate(el => {
          const css = getComputedStyle(el);
          return [css.fontFamily, css.fontSize, css.lineHeight];
        }),
        ['monospace', '20px', '40px'],
        'anonymous typography matches the actual Portal',
      );
    }
    assert.equal(
      await page
        .getByTestId('portal-color')
        .evaluate(el => getComputedStyle(el).fontFamily),
      'monospace',
      'actual Portal preserves the host font',
    );
    const portalColor = () =>
      page
        .getByTestId('portal-color')
        .evaluate(el => getComputedStyle(el).backgroundColor);
    assert.equal(await portalColor(), initial);
    await page.getByRole('textbox', { name: '保留输入' }).fill('keep me');
    await page.getByTestId('portal-color').click();
    await page.getByRole('textbox', { name: '保留输入' }).hover();
    await page.waitForFunction(
      () =>
        getComputedStyle(document.querySelector('[data-testid="portal-color"]'))
          .backgroundColor ===
        getComputedStyle(
          document.querySelector('[data-testid="primary-color"]'),
        ).backgroundColor,
    );
    assert.notEqual(await primary(), initial);
    assert.equal(
      await page.getByRole('textbox', { name: '保留输入' }).inputValue(),
      'keep me',
    );
    await page.getByRole('button', { name: '弹层切换明暗' }).click();
    await page.waitForFunction(
      () =>
        getComputedStyle(
          document
            .querySelector('[data-testid="portal-color"]')
            .closest('[role="dialog"]'),
        ).colorScheme === 'dark',
    );
    await page.waitForFunction(
      () =>
        getComputedStyle(document.querySelector('[data-testid="portal-color"]'))
          .backgroundColor ===
        getComputedStyle(
          document.querySelector('[data-testid="primary-color"]'),
        ).backgroundColor,
    );
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '跟随系统', exact: true }).click();
    await page.getByRole('button', { name: '主题弹层', exact: true }).click();
    await page.emulateMedia({ colorScheme: 'light' });
    await page.waitForFunction(
      () =>
        getComputedStyle(
          document
            .querySelector('[data-testid="portal-color"]')
            .closest('[role="dialog"]'),
        ).colorScheme === 'light',
    );
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForFunction(
      () =>
        getComputedStyle(
          document
            .querySelector('[data-testid="portal-color"]')
            .closest('[role="dialog"]'),
        ).colorScheme === 'dark',
    );
    await page.waitForFunction(
      () =>
        getComputedStyle(document.querySelector('[data-testid="portal-color"]'))
          .backgroundColor ===
        getComputedStyle(
          document.querySelector('[data-testid="primary-color"]'),
        ).backgroundColor,
    );
    await page.keyboard.press('Escape');
    const height = await page
      .getByTestId('primary-color')
      .evaluate(el => el.getBoundingClientRect().height);
    await page.getByRole('button', { name: '切换密度', exact: true }).click();
    await page.waitForFunction(
      previous =>
        document
          .querySelector('[data-testid="primary-color"]')
          .getBoundingClientRect().height < previous,
      height,
    );
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    );
    await page.screenshot({
      path:
        process.env.VIEW_ENGINE_THEME_SCREENSHOT ??
        '/tmp/fve-theme-compact.png',
      fullPage: true,
    });
    assert.equal(await note.inputValue(), 'retained business note');
    assert.ok(await selected.isChecked(), 'theme changes keep row selection');
    await page.setViewportSize({ width: 1280, height: 1000 });
    await page.evaluate(() => {
      document.body.style.zoom = '2';
    });
    assert.ok(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      '200% zoom does not overflow the document',
    );
    assert.deepEqual(errors, []);
  }
  console.log('Theme CSS and requested browser checks passed');
} finally {
  await browser.close();
}
