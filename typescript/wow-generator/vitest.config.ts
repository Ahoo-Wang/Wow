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
        // Held a point or two below what the suite measured on 2026-09-24
        // (statements 97.53, branches 93.33, functions 98.86, lines 98.35): a
        // coverage drop fails CI, a behaviour-preserving refactor does not.
        // Raise them as coverage rises, but never to 100.
        thresholds: {
          statements: 96,
          branches: 92,
          functions: 97,
          lines: 97,
        },
      },
    },
  }),
);
