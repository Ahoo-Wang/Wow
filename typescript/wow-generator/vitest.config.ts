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
          // Test support and fixtures are not the code under test; counting
          // them would let a helper's coverage hide a drop in src.
          'test/**',
          'scripts/**',
        ],
        // Held about half a point below what the suite measured on src alone
        // on 2026-09-24 (statements 97.53, branches 93.14, functions 99.27,
        // lines 98.44): a coverage drop fails CI, a behaviour-preserving
        // refactor does not. Raise them as coverage rises, but never to 100.
        thresholds: {
          statements: 97,
          branches: 92.5,
          functions: 98.5,
          lines: 98,
        },
      },
    },
  }),
);
