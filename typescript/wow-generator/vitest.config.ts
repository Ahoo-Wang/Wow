import { configDefaults, defineConfig, mergeConfig } from 'vitest/config';
import { resolve } from 'path';
import viteConfig from './vite.config';

export default mergeConfig(
  viteConfig,
  defineConfig({
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src'),
      },
    },
    test: {
      clearMocks: true,
      restoreMocks: true,
      unstubGlobals: true,
      coverage: {
        exclude: [
          ...configDefaults.exclude,
          'expected/**',
          'test-output/**',
          '**/**.stories.tsx',
        ],
      },
    },
  }),
);
