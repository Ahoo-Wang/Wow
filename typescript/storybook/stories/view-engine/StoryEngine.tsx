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
import { useEffect, useState, type ReactNode } from 'react';
import type { ViewEngine } from '@ahoo-wang/fetcher-view-engine';

/**
 * One engine for one mount, disposed with it.
 *
 * View Engine stories write for real — saving, renaming and deleting go
 * through the same commands an application uses — so a scenario that inherited
 * another's store would show the previous scenario's leftovers.
 */
export function StoryEngine({
  create,
  children,
}: {
  create: () => ViewEngine;
  children: (engine: ViewEngine) => ReactNode;
}) {
  const [engine] = useState(create);
  useEffect(() => () => engine.dispose(), [engine]);
  return <>{children(engine)}</>;
}

/** What every View Engine scene shares; a module narrows it. */
export const viewEngineScene = {
  domain: 'View Engine',
  summary: '配置代替页面：定义在代码里，视图配置是数据。',
  fixture: '内存 ViewStore · 假数据源',
  setup: '每次挂载都新建引擎与存储。',
  observe: '界面状态来自运行时快照，而不是组件内部状态。',
};
