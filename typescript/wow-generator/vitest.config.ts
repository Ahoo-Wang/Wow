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
      coverage: {
        exclude: [
          ...configDefaults.exclude,
          'expected/**',
          'test-output/**',
        ],
      },
    },
  }),
);