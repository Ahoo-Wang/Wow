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

import { useEffect, useRef, useState } from 'react';
import {
  Button,
  FilterSelect,
  IndexedDBViewHost,
  InputGroup,
  InputGroupInput,
  useViewEngine,
  ViewPage,
} from '@ahoo-wang/fetcher-view-engine/react';
import { connectCompensation } from './connection.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

type Connection = Awaited<ReturnType<typeof connectCompensation>>;
function ConnectedView({
  connection,
  baseURL,
}: {
  connection: Connection;
  baseURL: string;
}) {
  const [host] = useState(
    () =>
      new IndexedDBViewHost({
        serviceKey: `compensation:${baseURL}:${connection.model}`,
        scopeKey: 'local-demo',
        definition: connection.definition,
        instances: connection.instances,
        resolveSource: () => connection.source,
        instancePermissions: instance => ({
          save: instance.scope.type === 'personal',
          saveAsPersonal: true,
          saveAsShared: false,
          rename: instance.scope.type === 'personal',
          delete: instance.scope.type === 'personal',
        }),
      }),
  );
  const binding = useViewEngine({
    host,
    scopeKey: 'local-demo',
    definitionId: connection.definition.id,
  });
  return <ViewPage {...binding} />;
}
/** Explicit connection avoids background requests when opening the story catalog. */
export function CompensationExample({
  kind = 'analysis',
}: {
  kind?: 'record' | 'analysis';
}) {
  const [baseURL, setBaseURL] = useState(
    'http://compensation-service.dev.svc.cluster.local/',
  );
  const [model, setModel] = useState<'SNAPSHOT' | 'EVENT_STREAM'>('SNAPSHOT');
  const [connection, setConnection] = useState<Connection>();
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState('');
  const pending = useRef<AbortController | null>(null);
  useEffect(() => () => pending.current?.abort(), []);
  async function connect() {
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    setConnecting(true);
    setError('');
    try {
      const next = await connectCompensation({
        baseURL,
        kind,
        model,
        signal: controller.signal,
      });
      if (!controller.signal.aborted) setConnection(next);
    } catch {
      if (!controller.signal.aborted)
        setError('连接失败。请检查服务地址、端口转发、跨域设置和访问权限。');
    } finally {
      if (!controller.signal.aborted) setConnecting(false);
    }
  }
  return (
    <div className="fve-root fve:[&_p]:m-0 fve:flex fve:min-h-screen fve:flex-col fve:gap-4 fve:bg-muted/30 fve:p-4">
      {connection ? (
        <header className="fve:flex fve:flex-wrap fve:items-center fve:justify-between fve:gap-2 fve:rounded-xl fve:border fve:bg-background fve:px-4 fve:py-3">
          <div className="fve:flex fve:flex-wrap fve:items-center fve:gap-2">
            <strong>补偿控制台</strong>
            <span className="fve:text-sm fve:text-muted-foreground">
              {model === 'SNAPSHOT' ? '补偿记录快照' : '事件流与事件条目'} ·
              视图配置保存在本机
            </span>
          </div>
          <Button variant="outline" onClick={() => setConnection(undefined)}>
            断开连接
          </Button>
        </header>
      ) : (
        <header className="fve:flex fve:flex-col fve:gap-3 fve:rounded-xl fve:border fve:bg-background fve:p-4">
          <div>
            <h1 className="fve:text-xl fve:font-semibold">
              补偿控制台 · {kind === 'record' ? '数据' : '分析'}
            </h1>
            <p className="fve:mt-1 fve:text-sm fve:text-muted-foreground">
              查询数据来自所连接的服务；个人视图配置仅保存在本机浏览器。
            </p>
          </div>
          <form
            className="fve:flex fve:flex-wrap fve:items-end fve:gap-3"
            onSubmit={event => {
              event.preventDefault();
              void connect();
            }}
          >
            <label className="fve:flex fve:min-w-56 fve:flex-1 fve:flex-col fve:gap-1 fve:text-sm">
              服务地址
              <InputGroup>
                <InputGroupInput
                  type="url"
                  required
                  value={baseURL}
                  onChange={event => setBaseURL(event.target.value)}
                  disabled={!!connection || connecting}
                />
              </InputGroup>
            </label>
            {kind === 'analysis' && (
              <div className="fve:flex fve:flex-col fve:gap-1 fve:text-sm">
                <span>统计来源</span>
                <FilterSelect
                  label="统计来源"
                  value={model}
                  onValueChange={value => setModel(value as typeof model)}
                  options={[
                    { value: 'SNAPSHOT', label: '补偿记录快照' },
                    { value: 'EVENT_STREAM', label: '事件流与事件条目' },
                  ]}
                  disabled={!!connection || connecting}
                />
              </div>
            )}
            <Button type="submit" disabled={connecting}>
              {connecting ? '连接中…' : '连接 API'}
            </Button>
            {connecting && (
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  pending.current?.abort();
                  setConnecting(false);
                }}
              >
                取消
              </Button>
            )}
          </form>
          {error && (
            <p role="alert" className="fve:text-sm fve:text-destructive">
              {error}
            </p>
          )}
        </header>
      )}
      {connection ? (
        <ConnectedView connection={connection} baseURL={baseURL} />
      ) : (
        <section
          aria-label="连接说明"
          className="fve:rounded-xl fve:border fve:bg-background fve:p-8 fve:text-sm fve:text-muted-foreground"
        >
          <h2 className="fve:mb-2 fve:font-semibold fve:text-foreground">
            {kind === 'record'
              ? '连接服务，查看补偿记录'
              : '选择统计来源并连接服务'}
          </h2>
          {kind === 'record' ? (
            <p>
              使用真实快照分页查询，支持筛选补偿状态、服务端排序和翻页；不会执行补偿或修改业务记录。
            </p>
          ) : (
            <>
              <p>
                补偿记录快照：查看当前状态分布、平均重试次数和新增记录趋势。
              </p>
              <p>
                事件流与事件条目：根层级统计事件流批次；展开事件条目后统计每条事件。
              </p>
            </>
          )}
        </section>
      )}
    </div>
  );
}
