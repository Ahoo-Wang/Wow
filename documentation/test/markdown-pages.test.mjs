import assert from 'node:assert/strict'
import {existsSync, readFileSync} from 'node:fs'
import {test} from 'node:test'

const pages = [
    {html: 'articles/command-success-is-not-complete.html', markdown: 'articles/command-success-is-not-complete.md'},
    {html: 'zh/articles/command-success-is-not-complete.html', markdown: 'zh/articles/command-success-is-not-complete.md'},
    {html: 'zh/guide/getting-started.html', markdown: 'zh/guide/getting-started.md'},
    {html: 'zh/articles/index.html', markdown: 'zh/articles.md'},
    {html: 'zh/guide/index.html', markdown: 'zh/guide.md'},
    {html: 'zh/index.html', markdown: 'zh.md'},
]

test('built pages expose their Markdown URLs', () => {
    for (const page of pages) {
        const markdownPath = new URL(`../docs/.vitepress/dist/${page.markdown}`, import.meta.url)
        const htmlPath = new URL(`../docs/.vitepress/dist/${page.html}`, import.meta.url)
        assert.ok(existsSync(markdownPath), `Missing Markdown page: ${page.markdown}`)
        assert.ok(
            readFileSync(htmlPath, 'utf8').includes(`at /${page.markdown} for this page in Markdown format`),
            `HTML page does not reference ${page.markdown}`,
        )
    }
})
