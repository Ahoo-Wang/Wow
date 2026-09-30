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

import { expect, waitFor } from 'storybook/test';

/*
 * The strict Content Security Policy of the view engine's README
 * (「Content Security Policy」), put on the page a story runs in: no
 * `'unsafe-inline'` and no `'unsafe-eval'` anywhere, styles from the page's
 * own origin or carrying the page's nonce, images from the origin or a
 * `blob:`. The nonce is published the way Vite's `html.cspNonce` publishes
 * it, `<meta property="csp-nonce" nonce="…">` — what the engine reads.
 *
 * The policy is delivered as `<meta http-equiv>`, the one way a page already
 * loaded can take one: from then on the browser holds everything the page
 * adds to it, and a policy cannot be taken back, so it is only ever put on a
 * page of its own — the stories of `StrictCsp.test.stories.tsx`, which
 * Storybook's test runner loads in a frame of their own. What was on the
 * page before (the harness's own scripts and the stylesheets the preview
 * imports, which a host serves as files) is not judged; everything the
 * engine draws, adds and loads after is.
 */

/** The nonce this page publishes. */
export const CSP_NONCE = 'storybook-strict-csp';

/** The README's policy, word for word, with this page's nonce. */
export const STRICT_POLICY = [
  "default-src 'self'",
  "script-src 'self'",
  `style-src 'self' 'nonce-${CSP_NONCE}'`,
  "img-src 'self' blob:",
].join('; ');

/** One violation the browser reported, as a failure should read it. */
export interface Violation {
  directive: string;
  blocked: string;
  sample: string;
  source: string;
}

/**
 * What the test harness itself does to the page, and not the engine: the
 * accessibility addon's vision simulator puts its SVG filters into `<body>`
 * as markup carrying a `style` attribute, once per story. Named by the file
 * that reports it, so nothing the engine or its libraries do can hide here.
 */
const HARNESS = [/\/@storybook\/addon-a11y\//];

const seen: Violation[] = [];
let installed = false;

/**
 * Puts the policy on this page, once, and starts keeping every violation
 * it reports. Before it hands over, it proves the policy is in force: a
 * `<style>` without the nonce must be refused, or the stories would pass on
 * a page that holds nothing.
 */
export async function underStrictPolicy(): Promise<void> {
  if (!installed) {
    installed = true;
    document.addEventListener('securitypolicyviolation', event => {
      if (HARNESS.some(file => file.test(event.sourceFile))) return;
      seen.push({
        directive: event.effectiveDirective || event.violatedDirective,
        blocked: event.blockedURI,
        sample: event.sample,
        source: event.sourceFile
          ? `${event.sourceFile}:${event.lineNumber}:${event.columnNumber}`
          : '',
      });
    });
    const nonce = document.createElement('meta');
    nonce.setAttribute('property', 'csp-nonce');
    nonce.nonce = CSP_NONCE;
    document.head.append(nonce);
    const policy = document.createElement('meta');
    policy.httpEquiv = 'Content-Security-Policy';
    policy.content = STRICT_POLICY;
    document.head.append(policy);

    const canary = document.createElement('style');
    canary.textContent = '.strict-csp-canary { color: red; }';
    document.head.append(canary);
    await waitFor(() =>
      expect(
        seen.some(violation => violation.directive.startsWith('style-src')),
        'the policy refuses a <style> without the nonce',
      ).toBe(true),
    );
    canary.remove();
  }
  seen.length = 0;
}

/*
 * Motion, and why these stories run without it.
 *
 * Between a play and the checks after it, Storybook's runner freezes every
 * animation at its end (`pauseAnimations` in `storybook/preview-api`), so
 * the accessibility check reads a tooltip whole even if it only just opened.
 * It freezes them with two `<style>` elements, added without a nonce, and
 * the policy above refuses both — two `style-src-elem` reports from
 * Storybook's own code a story, after the play, cleared by the next
 * story's `underStrictPolicy` before anyone reads them. On this page alone,
 * then, nothing was frozen: a tooltip that opened as a play ended (focus
 * handed back to its trigger by a closing dialog opens it at once) was
 * measured still fading in, at 3.08:1 or 1.83:1 against 4.5:1.
 *
 * So the page is told the reader asked for less motion, which is a
 * setting a reader has and needs no stylesheet: the engine's own
 * `prefers-reduced-motion` rule cuts every popup's animation to nothing,
 * and a tooltip is either not there or whole whenever axe looks.
 */

/**
 * The reader's wish for less motion, on until `motionBack`. Nothing where
 * no test runner can emulate it (Storybook's own panel).
 */
export async function lessMotion(): Promise<void> {
  const emulator = globalThis.storybookMedia;
  if (!emulator) return;
  await emulator.emulate({ reducedMotion: 'reduce' });
  await expect(
    matchMedia('(prefers-reduced-motion: reduce)').matches,
    'the page is told the reader wants less motion',
  ).toBe(true);
}

/**
 * The motion preference handed back to the browser: the emulation is the
 * page's, and outlives the story (`.storybook/vitest.setup.ts` hands it back
 * again as each file begins).
 */
export async function motionBack(): Promise<void> {
  await globalThis.storybookMedia?.emulate({ reducedMotion: null });
}

/** Every violation reported since the story began. */
export const violations = (): readonly Violation[] => [...seen];

/** Not one violation so far; a failure lists each. */
export async function expectNoViolations(step: string): Promise<void> {
  // A violation is reported as a task of its own, after what caused it.
  await new Promise(resolve => setTimeout(resolve, 50));
  await expect(violations(), `${step}: CSP violations`).toEqual([]);
}
