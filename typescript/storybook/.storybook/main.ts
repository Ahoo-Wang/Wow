import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'module';
import type { StorybookConfig } from '@storybook/react-vite';
import { mergeConfig } from 'vite';

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
    mergeConfig(config, { resolve: { alias: workspaceAliases } }),
};
export default config;
