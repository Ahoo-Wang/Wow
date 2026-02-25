import { join, dirname } from 'path';
import { createRequire } from 'module';
import type { StorybookConfig } from '@storybook/react-vite';

const require = createRequire(import.meta.url);

function getAbsolutePath(value: string): string {
  return dirname(require.resolve(join(value, 'package.json')));
}

const config: StorybookConfig = {
  stories: [
    '../stories/**/*.mdx',
    '../stories/**/*.stories.@(js|jsx|mjs|ts|tsx)',
    '../packages/*/src/**/*.stories.@(js|jsx|mjs|ts|tsx)',
  ],
  addons: [
    getAbsolutePath('@chromatic-com/storybook'),
    {
      name: getAbsolutePath('@storybook/addon-docs'),
      options: {
        transcludeMarkdown: true,
      },
    },
    getAbsolutePath('@storybook/addon-onboarding'),
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
  async viteFinal(config) {
    config.assetsInclude = ['../**/*.md', '../*.md'];

    config.esbuild = {
      legalComments: 'none',
      jsx: 'automatic',
    };

    config.plugins = config.plugins || [];
    config.plugins.push({
      name: 'markdown-link-transform',
      transform(code, id) {
        if (id.endsWith('.md?raw')) {
          return code
            .replace(
              /\]\(\.\/packages\//g,
              '](https://github.com/Ahoo-Wang/fetcher/tree/main/packages/',
            )
            .replace(
              /\]\(\.\/integration-test/g,
              '](https://github.com/Ahoo-Wang/fetcher/tree/main/integration-test',
            )
            .replace(
              /\]\(\.\/CONTRIBUTING\.md/g,
              '](https://github.com/Ahoo-Wang/fetcher/blob/main/CONTRIBUTING.md',
            )
            .replace(
              /\]\(\.\/LICENSE/g,
              '](https://github.com/Ahoo-Wang/fetcher/blob/main/LICENSE',
            )
            .replace(
              /\]\(\.\//g,
              '](https://github.com/Ahoo-Wang/fetcher/blob/main/',
            );
        }
        return code;
      },
    });

    return config;
  },
};
export default config;
