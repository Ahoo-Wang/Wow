/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useState, type PropsWithChildren } from 'react';
import {
  DocsContainer,
  type DocsContainerProps,
} from '@storybook/addon-docs/blocks';
import { GLOBALS_UPDATED } from 'storybook/internal/core-events';
import { themes } from 'storybook/theming';

type Globals = Record<string, unknown>;

/** The docs context as the preview builds it: its store holds the globals. */
type Context = DocsContainerProps['context'] & {
  store?: { userGlobals?: { get(): Globals } };
};

/** Whether the reader's system asks for dark, followed while the page is open. */
function useSystemDark(): boolean {
  const [dark, setDark] = useState(
    () => matchMedia('(prefers-color-scheme: dark)').matches,
  );
  useEffect(() => {
    const query = matchMedia('(prefers-color-scheme: dark)');
    const follow = () => setDark(query.matches);
    query.addEventListener('change', follow);
    return () => query.removeEventListener('change', follow);
  }, []);
  return dark;
}

/**
 * The docs pages in the toolbar's mode. A story follows the mode through
 * `withMode` (`preview.tsx`), but a docs page that mounts no story — the
 * guided intro — never runs a decorator, and Storybook's docs theme is light
 * whatever the toolbar says. So the container reads the same `theme` global,
 * follows its changes, draws the docs in Storybook's dark theme when it is
 * dark, and puts `.dark` on `<html>` as `withMode` does, for what the page
 * draws itself (`.story-intro` in `preview.css`).
 */
export function ThemedDocsContainer(
  props: PropsWithChildren<DocsContainerProps>,
) {
  const context = props.context as Context;
  const [mode, setMode] = useState(() =>
    String(context.store?.userGlobals?.get().theme ?? 'light'),
  );
  useEffect(() => {
    const follow = ({ globals }: { globals: Globals }) =>
      setMode(String(globals.theme ?? 'light'));
    context.channel.on(GLOBALS_UPDATED, follow);
    return () => context.channel.off(GLOBALS_UPDATED, follow);
  }, [context.channel]);
  const systemDark = useSystemDark();
  const dark = mode === 'dark' || (mode === 'system' && systemDark);
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark);
  }, [dark]);
  return <DocsContainer {...props} theme={dark ? themes.dark : themes.light} />;
}
