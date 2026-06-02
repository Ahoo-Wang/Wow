import { defineConfig } from "vite";
import tailwindcss from "@tailwindcss/vite";

import react, { reactCompilerPreset } from "@vitejs/plugin-react";
import babel from "@rolldown/plugin-babel";

// https://vite.dev/config/
export default defineConfig({
  oxc: {
    decorator: {
      legacy: true,
      emitDecoratorMetadata: true,
    },
  },
  plugins: [
    react(),
    babel({
      plugins: [["@babel/plugin-syntax-decorators", { legacy: true }]],
      presets: [reactCompilerPreset()],
    }),
    tailwindcss(),
  ],
  optimizeDeps: {
    include: ["monaco-editor"],
    exclude: ["@monaco-editor/react"],
  },
});
