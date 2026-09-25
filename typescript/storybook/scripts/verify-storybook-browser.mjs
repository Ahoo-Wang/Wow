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
const origin = (process.argv[2] ?? 'http://127.0.0.1:6006').replace(/\/$/, '');
const HOST_NAV = 'nav[aria-label="应用导航"]';
const HOST_LINKS = `${HOST_NAV} a[href^="./?path="]`;
/** The story id a `./?path=/story/<id>` or `/docs/<id>` link lands on. */
const target = href =>
  new URL(href, `${origin}/`).searchParams
    .get('path')
    .replace(/^\/(docs|story)\//, '');
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const index = await (await fetch(`${origin}/index.json`)).json();
    const entries = Object.values(index.entries);
    // A story without the `test` tag talks to a live service (the 真实后端
    // scenes); it is checked to exist, never opened.
    const offline = id => index.entries[id].tags.includes('test');
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1000 },
    });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    async function open(id, mode = 'story', globals) {
      await page.goto(
        `${origin}/iframe.html?id=${encodeURIComponent(id)}&viewMode=${mode}` +
          (globals ? `&globals=${globals}` : ''),
        { waitUntil: 'domcontentloaded' },
      );
      assert.equal(await page.locator('#error-message').isVisible(), false, id);
    }
    async function hrefs(selector) {
      return page
        .locator(selector)
        .evaluateAll(a => a.map(x => x.getAttribute('href')));
    }

    // The View Engine home: the host's page, with its dashboard.
    const home = entries.find(
      e =>
        e.type === 'story' &&
        e.importPath.endsWith('/view-engine/Home.stories.tsx') &&
        e.exportName === 'Fixture',
    );
    assert.ok(home, 'View Engine home (Home.stories.tsx Fixture) missing');
    await open(home.id);
    await page
      .getByRole('heading', { name: '运营概览', exact: true })
      .waitFor();
    await page.getByText('本月每日新增失败', { exact: true }).waitFor();
    assert.equal(
      target(
        await page
          .locator(`${HOST_NAV} a[aria-current="page"]`)
          .getAttribute('href'),
      ),
      home.id,
      'The home page marks its own navigation link current',
    );

    // Every host navigation link lands on an indexed story, in the top frame.
    const links = await hrefs(HOST_LINKS);
    assert.ok(links.length > 1, 'Host navigation has no links');
    for (const href of links) {
      assert.ok(index.entries[target(href)], `Missing target: ${href}`);
      assert.equal(
        await page
          .locator(`${HOST_NAV} a[href="${href}"]`)
          .getAttribute('target'),
        '_top',
        href,
      );
    }
    console.log(`Host navigation: ${links.length} links resolve`);

    // Each offline scene renders, carries the same navigation and marks its
    // own link current.
    for (const href of links) {
      const id = target(href);
      if (!offline(id)) continue;
      await open(id);
      await page.locator(`${HOST_NAV} a[aria-current="page"]`).waitFor();
      assert.deepEqual(await hrefs(HOST_LINKS), links, `${id}: navigation`);
      assert.equal(
        target(
          await page
            .locator(`${HOST_NAV} a[aria-current="page"]`)
            .getAttribute('href'),
        ),
        id,
        `${id}: current link`,
      );
    }
    console.log('Host navigation: every offline scene marks itself current');

    // A click moves the whole Storybook, as a host's navigation moves the
    // whole page.
    const next = links.map(target).find(id => id !== home.id && offline(id));
    await page.goto(`${origin}/?path=/story/${encodeURIComponent(home.id)}`);
    const preview = page.frameLocator('#storybook-preview-iframe');
    await preview
      .getByRole('heading', { name: '运营概览', exact: true })
      .waitFor();
    await preview
      .locator(`${HOST_NAV} a[href="./?path=/story/${next}"]`)
      .click();
    await page.waitForURL(url =>
      decodeURIComponent(url.search).includes(`/story/${next}`),
    );
    await preview.locator(`${HOST_NAV} a[aria-current="page"]`).waitFor();
    assert.equal(
      target(
        await preview
          .locator(`${HOST_NAV} a[aria-current="page"]`)
          .getAttribute('href'),
      ),
      next,
    );
    console.log(`Host navigation: clicking lands on ${next}`);

    // Narrow screens: no horizontal scroll.
    await page.setViewportSize({ width: 414, height: 896 });
    await open(home.id);
    await page
      .getByRole('heading', { name: '运营概览', exact: true })
      .waitFor();
    assert(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
      'Home scrolls sideways at 414px',
    );
    await page.setViewportSize({ width: 1440, height: 1000 });

    // Dark mode, from the toolbar's global, reaches the host's page.
    await open(home.id, 'story', 'theme:dark');
    await page
      .getByRole('heading', { name: '运营概览', exact: true })
      .waitFor();
    await page.waitForFunction(() =>
      document.documentElement.classList.contains('dark'),
    );
    const luminance = await page.locator('[data-host-page]').evaluate(el => {
      // The tokens are oklch; a canvas pixel turns any CSS color into sRGB.
      const context = document.createElement('canvas').getContext('2d');
      context.fillStyle = getComputedStyle(el).backgroundColor;
      context.fillRect(0, 0, 1, 1);
      const [r, g, b] = context.getImageData(0, 0, 1, 1).data;
      return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
    });
    assert.ok(luminance < 0.3, `Dark home background too light: ${luminance}`);
    console.log('Home: narrow layout and dark mode passed');

    // Docs pages: every one renders, and its scene list resolves.
    for (const doc of entries.filter(entry => entry.type === 'docs')) {
      await open(doc.id, 'docs');
      await page.locator('.sbdocs-content').waitFor();
      await page.getByRole('navigation', { name: '独立场景' }).waitFor();
      const scenes = await hrefs('nav[aria-label="独立场景"] a');
      assert.ok(scenes.length > 0, `${doc.title}: no scenes`);
      for (const href of scenes)
        assert.ok(index.entries[target(href)], `${doc.title}: ${href}`);
      if (doc.id === home.id.replace(/--[^-]+$/, '--docs'))
        await page
          .getByRole('heading', { name: '运营概览', exact: true })
          .waitFor();
      console.log('Docs verified:', doc.title);
    }

    assert.deepEqual(errors, []);
    console.log(
      'Home, host navigation, narrow layout, dark mode and docs pages: passed',
    );
  } finally {
    await browser.close();
  }
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
