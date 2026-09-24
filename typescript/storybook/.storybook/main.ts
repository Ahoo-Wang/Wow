import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'module';
import type { StorybookConfig } from '@storybook/react-vite';
import { mergeConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';
import { scopeUtilities } from '../packages/view-engine/scripts/scope-utilities.mjs';

const require = createRequire(import.meta.url);
const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const workspaceAliases = Object.fromEntries(
  [
    'cosec',
    'decorator',
    'eventbus',
    'eventstream',
    'fetcher',
    'generator',
    'openai',
    'openapi',
    'react',
    'storage',
    'view-engine',
    'viewer',
    'wow',
  ].map(directory => [
    directory === 'fetcher'
      ? '@ahoo-wang/fetcher'
      : `@ahoo-wang/fetcher-${directory}`,
    join(projectRoot, 'packages', directory, 'src', 'index.ts'),
  ]),
);

function getAbsolutePath(value: string): string {
  return dirname(require.resolve(join(value, 'package.json')));
}

const config: StorybookConfig = {
  // The developer highlighter scans all DOM styles on mutations, even without targets.
  features: { highlight: process.env.VIEW_ENGINE_ACCEPTANCE !== 'true' },
  stories: ['../stories/**/*.stories.@(ts|tsx)'],
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
          '@ahoo-wang/fetcher-react/core': join(
            projectRoot,
            'packages/react/src/core/index.ts',
          ),
          // The vendored shadcn components import each other through the
          // `@/ui/...` alias the registry writes; only View Engine uses it.
          '@/ui': join(projectRoot, 'packages/view-engine/src/ui'),
          // Subpath entries resolve to source like the roots above them.
          '@ahoo-wang/fetcher-view-engine/styles.css': join(
            projectRoot,
            'packages/view-engine/src/styles.css',
          ),
          '@ahoo-wang/fetcher-view-engine/react': join(
            projectRoot,
            'packages/view-engine/src/react/index.ts',
          ),
          '@ahoo-wang/fetcher-view-engine/ui': join(
            projectRoot,
            'packages/view-engine/src/ui/index.ts',
          ),
          ...workspaceAliases,
        },
      },
    }),
};
export default config;
