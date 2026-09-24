// Storybook is a separate app that the deploy assembles at /storybook/ after
// VitePress builds, so its pages are not VitePress pages.
export const STORYBOOK_PREFIX = '/storybook/'

export const isStorybookLink = (url) => url.startsWith(STORYBOOK_PREFIX)

// Pages link to stories with `/storybook/?path=/docs/<id>` (or `/story/<id>`).
// VitePress's own renderer still sees each link first, so it applies the base
// and records it for the dead-link check (which `ignoreDeadLinks` skips; see
// test/storybook-links.test.mjs for the check against Storybook's index.json).
// The link is then rendered again with `target="_blank"`, like the nav entry,
// so the VitePress router leaves it to the browser instead of looking for a page.
export function storybookLinks(md) {
    const render = md.renderer.rules.link_open ??
        ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
    md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
        const token = tokens[idx]
        const href = token.attrGet('href')
        if (!href || !isStorybookLink(href) || token.attrGet('target')) {
            return render(tokens, idx, options, env, self)
        }
        render(tokens, idx, options, env, self)
        token.attrSet('target', '_blank')
        return self.renderToken(tokens, idx, options)
    }
}
