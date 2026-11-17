import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'module';
import type { StorybookConfig } from '@storybook/react-vite';
import { mergeConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';
import { scopeUtilities } from '../../wow-view-engine/scripts/scope-utilities.mjs';
import { sourceAliases } from './sourceAliases';

const require = createRequire(import.meta.url);
// typescript/: the workspace packages the stories render from source.
const packagesRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Wow's own packages resolve to their sources, every entry they export;
// fetcher's come from npm.
const workspaceAliases = sourceAliases(packagesRoot, [
  'wow-client',
  'wow-react',
  'wow-view-engine',
  'wow-view-store',
]);

function getAbsolutePath(value: string): string {
  return dirname(require.resolve(join(value, 'package.json')));
}

const config: StorybookConfig = {
  features: {
    // The developer highlighter scans all DOM styles on mutations, even without targets.
    highlight: process.env.VIEW_ENGINE_ACCEPTANCE !== 'true',
    // The site is built in docs mode, whose sidebar holds docs pages only, so
    // its search finds a story by its name only through the anchors this flag
    // adds to each docs page (storybookjs/storybook#33593). It is experimental:
    // if it is renamed, set the new name here; if it is removed, check whether
    // docs mode searches story names on its own, else say in the guided intro
    // that the search covers page titles only. `scripts/verify-storybook.mjs`
    // fails the build when the anchors are missing.
    experimentalSearchDocsHeadings: true,
  },
  // `View Engine/导览` is a page of its own (`stories/view-engine/Intro.mdx`).
  stories: ['../stories/**/*.mdx', '../stories/**/*.stories.@(ts|tsx)'],
  addons: [
    getAbsolutePath('@chromatic-com/storybook'),
    {
      name: getAbsolutePath('@storybook/addon-docs'),
      options: {
        transcludeMarkdown: true,
      },
    },
    getAbsolutePath('@storybook/addon-a11y'),
    getAbsolutePath('@storybook/addon-themes'),
    getAbsolutePath('@storybook/addon-vitest'),
  ],
  typescript: {
    reactDocgen: 'react-docgen-typescript',
  },
  framework: {
    name: getAbsolutePath('@storybook/react-vite'),
    options: {},
  },
  viteFinal: config =>
    mergeConfig(config, {
      // View Engine's theme is a Tailwind stylesheet; the plugins that build
      // the package build it here too, so the stories show the real thing —
      // every painting rule pinned inside `.fve-root`.
      plugins: [tailwindcss()],
      css: { postcss: { plugins: [scopeUtilities()] } },
      server: { watch: { ignored: ['**/coverage/**'] } },
      resolve: {
        alias: [
          // The vendored shadcn components import each other through the
          // `@/ui/...` alias the registry writes; only View Engine uses it.
          {
            find: '@/ui',
            replacement: join(packagesRoot, 'wow-view-engine/src/ui'),
          },
          ...workspaceAliases,
        ],
      },
    }),
};
export default config;
