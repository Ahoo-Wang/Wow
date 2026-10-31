---
title: Content Security Policy
description: "What a strict Content Security Policy must allow for the view engine — its stylesheet as a file, a nonce for the styles its libraries add, a blob: image for the PNG export — and the tests that hold it to that."
---

# Content Security Policy

The view engine runs under a strict Content Security Policy: `script-src 'self'` and `style-src 'self'`, with neither `'unsafe-inline'` nor `'unsafe-eval'`. It evaluates no code from strings and writes no inline script. Three things need allowing, each for a reason.

## What to allow

- **The stylesheet is a file.** Serve `styles.css`, and `themes.css` or `themes/<name>.css` if you use a preset, from an allowed origin; do not inline them. Nothing the engine draws carries a `style` attribute in its markup: inline styles go through the DOM's style object, which no policy blocks. Nothing loads a `data:` image either.
- **The styles its libraries add carry the page's nonce.** Three of its dependencies add a `<style>` element while they work: the drag-and-drop library while a list is dragged, the grid library while a board's panel is moved or resized, and Base UI while a select's list is open. Publish the response's nonce as `<meta property="csp-nonce" nonce="…">`, as Vite's `html.cspNonce` does, and allow it in `style-src`; the engine hands it to all three (for the grid, it writes that style itself). Without it everything still works, but those few rules are refused and each reports a violation.
- **The PNG export loads a `blob:` image.** A chart's SVG is loaded as an image from a `blob:` URL and drawn onto a canvas, so `img-src` must include `blob:`. Without it, the PNG is not made and the toolbar says so. The SVG export needs nothing.

## The policy

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'nonce-<new every response>'; img-src 'self' blob:
```

```html
<meta property="csp-nonce" nonce="<the same nonce>" />
```

The nonce is new for every response, written into the header and the page's meta alike by whatever renders the HTML. A nonce that never changes is no nonce.

## The tests that hold it

In Storybook, [`StrictCsp.test.stories.tsx`](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/storybook/stories/view-engine/StrictCsp.test.stories.tsx) puts exactly this policy on the page and fails on a single violation while it walks the record workbench (dragging a column, picking from a select, widening a column, opening a record), every chart type with its tooltip, the SVG, PNG and CSV exports, and a board read, filtered, rearranged and saved. It runs in CI with the other stories.
