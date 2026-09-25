import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'module';
import type { StorybookConfig } from '@storybook/react-vite';
import { mergeConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';
import { scopeUtilities } from '../../wow-view-engine/scripts/scope-utilities.mjs';

const require = createRequire(import.meta.url);
// typescript/: the workspace packages the stories render from source.
const packagesRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
// Wow's own packages resolve to their sources; fetcher's come from npm.
const workspaceAliases = Object.fromEntries(
  ['wow-client', 'wow-react', 'wow-view-engine'].map(directory => [
    `@ahoo-wang/${directory}`,
    join(packagesRoot, directory, 'src', 'index.ts'),
  ]),
);

function getAbsolutePath(value: string): string {
  return dirname(require.resolve(join(value, 'package.json')));
}

const config: StorybookConfig = {
  // The developer highlighter scans all DOM styles on mutations, even without targets.
  features: { highlight: process.env.VIEW_ENGINE_ACCEPTANCE !== 'true' },
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
        alias: {
          // The vendored shadcn components import each other through the
          // `@/ui/...` alias the registry writes; only View Engine uses it.
          '@/ui': join(packagesRoot, 'wow-view-engine/src/ui'),
          // Subpath entries resolve to source like the roots above them.
          '@ahoo-wang/wow-view-engine/styles.css': join(
            packagesRoot,
            'wow-view-engine/src/styles.css',
          ),
          '@ahoo-wang/wow-view-engine/themes.css': join(
            packagesRoot,
            'wow-view-engine/src/themes.css',
          ),
          '@ahoo-wang/wow-view-engine/shadcn-bridge.css': join(
            packagesRoot,
            'wow-view-engine/src/shadcn-bridge.css',
          ),
          '@ahoo-wang/wow-view-engine/react': join(
            packagesRoot,
            'wow-view-engine/src/react/index.ts',
          ),
          '@ahoo-wang/wow-view-engine/ui': join(
            packagesRoot,
            'wow-view-engine/src/ui/index.ts',
          ),
          ...workspaceAliases,
        },
      },
    }),
};
export default config;
