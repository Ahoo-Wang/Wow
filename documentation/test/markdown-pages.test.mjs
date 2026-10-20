import assert from 'node:assert/strict'
import {existsSync, readFileSync} from 'node:fs'
import {test} from 'node:test'

// `markdown` is where the "View as Markdown" button fetches from: the plugin's
// client resolves the page URL without its trailing slash or `.html`, plus `.md`.
const pages = [
    {html: 'guide/v10/quickstart.html', markdown: 'guide/v10/quickstart.md'},
    {html: 'zh/guide/v10/quickstart.html', markdown: 'zh/guide/v10/quickstart.md'},
    {html: 'zh/guide/v10/index.html', markdown: 'zh/guide/v10.md'},
    {html: 'zh/index.html', markdown: 'zh.md'},
]

const dist = (path) => new URL(`../docs/.vitepress/dist/${path}`, import.meta.url)

test('built pages expose their Markdown URLs', () => {
    for (const page of pages) {
        assert.ok(existsSync(dist(page.markdown)), `Missing Markdown page: ${page.markdown}`)
        // vitepress-plugin-llms advertises each page's Markdown with this link.
        assert.ok(
            readFileSync(dist(page.html), 'utf8').includes(`<link href="/${page.markdown}" rel="alternate" type="text/markdown">`),
            `HTML page does not reference ${page.markdown}`,
        )
    }
})
