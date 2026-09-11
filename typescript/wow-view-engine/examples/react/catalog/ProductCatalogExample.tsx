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

import { ExampleViewPage } from '../ExampleViewPage.js';
import { IndexedDBViewHost } from '@ahoo-wang/fetcher-view-engine/react';
import { createContext, useContext, useRef, useState } from 'react';
import { HeartIcon } from 'lucide-react';
import { type ViewHost } from '@ahoo-wang/fetcher-view-engine';
import {
  Button,
  Popover,
  PopoverTrigger,
  PopoverContent,
  PopoverTitle,
  type RowActionsRendererProps,
  type RecordCardRenderContext,
  type ViewExtensions,
  type ToolbarActionsRendererProps,
} from '@ahoo-wang/fetcher-view-engine/react';
import { createCatalog, definition, views } from './catalog.js';
import '@ahoo-wang/fetcher-view-engine/styles.css';

type ProductCatalogExampleProps = {
  appearance?: 'light' | 'dark';
  custom?: boolean;
  persistViews?: boolean;
};

export function ProductCatalogExample(props: ProductCatalogExampleProps) {
  const [catalog] = useState(createCatalog);
  return (
    <CatalogWorkspace
      key={String(props.persistViews ?? false)}
      {...props}
      catalog={catalog}
    />
  );
}

function CatalogWorkspace({
  appearance = 'light',
  custom = false,
  persistViews = false,
  catalog,
}: ProductCatalogExampleProps & { catalog: ReturnType<typeof createCatalog> }) {
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [generation, setGeneration] = useState(0);
  const [host] = useState<ViewHost>(() =>
    persistViews
      ? new IndexedDBViewHost({
          scopeKey: 'demo:product-catalog',
          serviceKey: 'catalog-v1',
          definition,
          instances: views,
          resolveSource: () => catalog.source,
        })
      : {
          resolveSource: () => catalog.source,
          permission: {
            getInstance: () => ({
              save: false,
              saveAsPersonal: false,
              saveAsShared: false,
            }),
          },
        },
  );
  async function run(
    write: () => void,
    refresh: () => Promise<void>,
    message: string,
  ) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    try {
      write();
      setNotice(message);
      await refresh();
    } catch (error) {
      setError(error instanceof Error ? error.message : '商品操作失败');
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <ProductOperationsContext.Provider value={{ catalog, busy, run }}>
      <div
        className="fve-root"
        data-theme={appearance}
        style={{
          padding: 12,
          background: 'var(--fve-background)',
          color: 'var(--fve-foreground)',
        }}
      >
        <p className="fve:text-sm fve:text-muted-foreground">
          家居与出行 · 12 件生活好物 · 本地示意商品，操作仅在本页生效
        </p>
        {notice && <p role="status">{notice}</p>}
        {error && <p role="alert">{error}</p>}
        {persistViews && (
          <Button
            variant="outline"
            onClick={() => setGeneration(value => value + 1)}
          >
            重新打开已保存视图
          </Button>
        )}
        <ExampleViewPage
          key={generation}
          scopeKey="demo:product-catalog"
          definitionId={definition.id}
          {...(!persistViews && { definition, instances: views })}
          host={host}
          extensions={productExtensions}
          renderCard={custom ? renderProductCard : undefined}
          selectable
          initialSidebarCollapsed
          autoRefreshPaused={busy}
        />
      </div>
    </ProductOperationsContext.Provider>
  );
}

const ProductOperationsContext = createContext<{
  catalog: ReturnType<typeof createCatalog>;
  busy: boolean;
  run(
    write: () => void,
    refresh: () => Promise<void>,
    message: string,
  ): Promise<void>;
} | null>(null);
function useProductOperations() {
  const value = useContext(ProductOperationsContext);
  if (!value) throw new Error('商品操作需要业务上下文');
  return value;
}
function ProductActions({
  record,
  rowKey,
  refresh,
}: Pick<RowActionsRendererProps, 'record' | 'rowKey' | 'refresh'>) {
  const { catalog, busy, run } = useProductOperations();
  const published = record.status === 'published';
  return (
    <>
      <Popover>
        <PopoverTrigger
          render={
            <Button
              variant="outline"
              size="sm"
              aria-label={`查看商品 ${rowKey}`}
            />
          }
        >
          详情
        </PopoverTrigger>
        <PopoverContent aria-label={`商品详情 ${rowKey}`}>
          <PopoverTitle>{String(record.name)}</PopoverTitle>
          <img
            src={String(record.cover)}
            alt={String(record.name)}
            style={{ width: '100%', borderRadius: 8 }}
          />
          <p>
            {String(rowKey)} · {String(record.category)}
          </p>
          <p>
            售价 ¥{String(record.price)} · 库存 {String(record.stock)} 件
          </p>
          <p>本地演示商品，封面为原创矢量示意图。</p>
        </PopoverContent>
      </Popover>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-disabled={busy}
        className="fve:aria-disabled:opacity-50"
        aria-label={`收藏商品 ${rowKey}`}
        aria-pressed={Boolean(record.favorite)}
        title={record.favorite ? '取消收藏' : '收藏'}
        onClick={() =>
          void run(
            () => catalog.update([rowKey], { favorite: !record.favorite }),
            refresh,
            record.favorite ? '已取消收藏' : '已收藏商品',
          )
        }
      >
        <HeartIcon
          aria-hidden="true"
          fill={record.favorite ? 'currentColor' : 'none'}
        />
      </Button>
      <Button
        variant="ghost"
        size="sm"
        aria-disabled={busy}
        className="fve:aria-disabled:opacity-50"
        aria-label={`${published ? '下架' : '上架'}商品 ${rowKey}`}
        onClick={() =>
          void run(
            () =>
              catalog.update([rowKey], {
                status: published ? 'draft' : 'published',
              }),
            refresh,
            `${published ? '已下架' : '已上架'} ${rowKey}`,
          )
        }
      >
        {published ? '下架' : '上架'}
      </Button>
    </>
  );
}
function ProductBatchActions({
  selectedRowKeys,
  refresh,
  querying,
}: ToolbarActionsRendererProps) {
  const { catalog, busy, run } = useProductOperations();
  return (
    <>
      {(['published', 'draft'] as const).map(status => (
        <Button
          key={status}
          variant="outline"
          size="sm"
          disabled={querying || !selectedRowKeys.length}
          aria-disabled={busy}
          className="fve:aria-disabled:opacity-50"
          onClick={() =>
            void run(
              () => catalog.update(selectedRowKeys, { status }),
              refresh,
              `已批量${status === 'published' ? '上架' : '下架'} ${selectedRowKeys.length} 件商品`,
            )
          }
        >
          批量{status === 'published' ? '上架' : '下架'}
        </Button>
      ))}
    </>
  );
}
function renderProductCard({
  record,
  rowKey,
  refresh,
  selected,
}: RecordCardRenderContext) {
  return (
    <article className="fve:flex fve:h-full fve:flex-col">
      <img
        src={String(record.cover)}
        alt={String(record.name)}
        className="fve:aspect-video fve:w-full fve:object-cover"
      />
      <div className="fve:flex fve:flex-1 fve:flex-col fve:gap-2 fve:p-4">
        <span className="fve:text-xs fve:text-muted-foreground">
          {selected ? '已选商品' : String(record.category)} ·{' '}
          {record.status === 'published' ? '已上架' : '待上架'}
        </span>
        <h2 className="fve:m-0 fve:text-base fve:font-semibold">
          {String(record.name)}
        </h2>
        <div className="fve:flex fve:items-baseline fve:justify-between">
          <strong className="fve:text-2xl">¥{String(record.price)}</strong>
          <span
            className={
              record.stock === 0
                ? 'fve:text-destructive'
                : 'fve:text-muted-foreground'
            }
          >
            {record.stock === 0 ? '暂时缺货' : `库存 ${record.stock}`}
          </span>
        </div>
        <div className="fve:mt-auto fve:flex fve:items-center fve:gap-2 fve:border-t fve:pt-3">
          <ProductActions record={record} rowKey={rowKey} refresh={refresh} />
        </div>
      </div>
    </article>
  );
}

const productExtensions: ViewExtensions = {
  rowActions: { 'product-actions': ProductActions },
  toolbarActions: { 'product-batch': ProductBatchActions },
};
