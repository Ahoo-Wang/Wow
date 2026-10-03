---
title: Content Security Policy for the View Engine
description: "wow-view-engine runs under a strict Content Security Policy — the stylesheet a file, the bundled libraries' styles carrying a nonce, a blob: image for the PNG export — and the tests that hold it to that policy."
---

# Content Security Policy for the View Engine

This page answers: **what does the view engine need allowed when the page runs a strict Content Security Policy (CSP)?**

The engine runs under a strict Content Security Policy: `script-src 'self'` and `style-src 'self'`, with neither `'unsafe-inline'` nor `'unsafe-eval'`. It evaluates no code from strings and writes no inline script; three things need allowing, each for a reason.

## The three things to allow

- **The stylesheet is a file.** Serve `styles.css` (and `themes.css` or `themes/<name>.css` if you use a preset) from an allowed origin instead of inlining it. Nothing the engine draws carries a `style` attribute in its markup: inline styles are written through the DOM's style object, which no policy blocks, and a chart tooltip's colour swatch is an SVG `fill`. Nothing loads a `data:` image either; the cells a board shows while it is built are drawn in the page.
- **The styles the bundled libraries add carry the page's nonce.** Three libraries add a `<style>` to `<head>` while they work: the drag-and-drop library while a list is dragged (a grabbing cursor, no text selection), the grid's drag library while a dashboard panel is moved or resized (no text selection), and Base UI while a select's list is open (the scrollbar hidden behind its scroll arrows). Publish the response's nonce the way Vite's `html.cspNonce` does, as `<meta property="csp-nonce" nonce="…">` (a `content` attribute is read too), and allow `'nonce-…'` in `style-src`; the engine hands it to all three. Without the meta everything still works, but those few rules are refused and each reports a violation.
- **The PNG export loads a `blob:` image.** The chart's SVG is loaded as an image from a `blob:` URL and drawn onto a canvas, so `img-src` must include `blob:`. Without it the PNG is not made and the toolbar says so. The SVG export needs nothing, and neither export evaluates code or writes an inline script.

## The policy

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'nonce-<new every response>'; img-src 'self' blob:
```

```html
<meta property="csp-nonce" nonce="<the same nonce>" />
```

The nonce is new for every response, written by the server (or whatever renders the HTML) into the response header and the page's meta alike; a nonce that never changes is no nonce at all.

## The tests that hold it

Two test runs hold the engine to exactly this policy and fail on a single violation:

- In Storybook, [`StrictCsp.test.stories.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/StrictCsp.test.stories.tsx) walks the record workbench (a column dragged, a summary picked from a select, a column widened, a record's detail opened), every chart type with its tooltip, the SVG, PNG and CSV exports, and a dashboard read, filtered from a chart, built (a panel moved and resized, a tab dragged, an analysis added) and saved; it runs in CI with the other stories. [`strictCsp.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/strictCsp.ts) puts the policy on the page at the start of each story.
- The compensation console runs its end-to-end tests under the same policy ([`e2e/csp.spec.ts`](https://github.com/Ahoo-Wang/Wow/blob/main/compensation/dashboard/e2e/csp.spec.ts)).

## Next

| Next | Read |
|---|---|
| Which stylesheets there are, and when to import each | [Theming the View Engine](./view-engine-theming.md) |
| `ViewHost`, routes and embeds | [Fitting the View Engine into a Host](./view-engine-host.md) |
| What the view engine is | [View Engine](./view-engine.md) |
