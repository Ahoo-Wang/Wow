/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

export async function verifyIndexedDBViewHost({
  page,
  bobPage,
  origin,
  fixture,
}) {
  const coreUrl =
    origin +
    '/@fs' +
    fileURLToPath(new URL('../dist/react.js', import.meta.url));
  const databaseName = `cas-${crypto.randomUUID()}`;
  for (const tab of [page, bobPage]) {
    await tab.evaluate(
      async ({ coreUrl, fixture, databaseName }) => {
        const { IndexedDBViewHost } = await import(coreUrl);
        window.storageHost = new IndexedDBViewHost({
          ...fixture,
          databaseName,
          scopeKey: 'alice',
          serviceKey: 'native-cas-contract',
          resolveSource: () => {
            throw new Error('not needed');
          },
        });
      },
      { coreUrl, fixture, databaseName },
    );
  }
  const load = () =>
    page.evaluate(() => window.storageHost.instance.load('my-orders'));
  // Same old version, independent renderer processes, repeated without sleeps or retrying assertions.
  for (let round = 0; round < 50; round++) {
    const old = await load();
    const race = await Promise.all(
      [page, bobPage].map((tab, index) =>
        tab.evaluate(
          async ({ old, round, index }) => {
            try {
              const saved = await window.storageHost.instance.save({
                ...old,
                title: `writer-${round}-${index}`,
              });
              return { outcome: 'saved', revision: saved.revision };
            } catch (error) {
              return { outcome: error.code };
            }
          },
          { old, round, index },
        ),
      ),
    );
    assert.deepEqual(race.map(r => r.outcome).sort(), [
      'REVISION_CONFLICT',
      'saved',
    ]);
    assert.equal(
      (await load()).revision,
      race.find(r => r.outcome === 'saved').revision,
    );
  }
  const old = await load();
  const created = await Promise.all(
    [page, bobPage].map(tab =>
      tab.evaluate(
        input =>
          window.storageHost.instance.create(input, {
            requestId: 'same-create',
          }),
        old,
      ),
    ),
  );
  assert.equal(created[0].id, created[1].id);
  const changed = await bobPage.evaluate(async input => {
    try {
      await window.storageHost.instance.create(
        { ...input, title: 'different' },
        { requestId: 'same-create' },
      );
    } catch (error) {
      return error.code;
    }
  }, old);
  assert.equal(changed, 'CONFLICT');
  // Failure after the write request succeeds must still roll back and reject the caller.
  const rollback = await page.evaluate(async old => {
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (...args) {
      const request = put.apply(this, args);
      request.addEventListener('success', () => this.transaction.abort());
      return request;
    };
    try {
      await window.storageHost.instance.save({
        ...old,
        title: 'must-roll-back',
      });
      return 'saved';
    } catch (error) {
      return error.code;
    } finally {
      IDBObjectStore.prototype.put = put;
    }
  }, old);
  assert.equal(rollback, 'UNAVAILABLE');
  assert.equal((await load()).revision, old.revision);

  await page.evaluate(databaseName => {
    window.heldTransaction = new Promise((resolve, reject) => {
      const request = indexedDB.open(databaseName, 1);
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction('states', 'readwrite');
        let keep = true;
        window.releaseTransaction = () => {
          keep = false;
        };
        const pump = () => {
          if (keep) tx.objectStore('states').get('hold').onsuccess = pump;
        };
        tx.oncomplete = () => {
          db.close();
          resolve();
        };
        tx.onabort = () => {
          db.close();
          reject(tx.error);
        };
        pump();
      };
    });
  }, databaseName);
  await page.waitForFunction(
    () => typeof window.releaseTransaction === 'function',
  );
  try {
    await bobPage.evaluate(input => {
      window.transactionQueued = false;
      window.originalTransaction = IDBDatabase.prototype.transaction;
      IDBDatabase.prototype.transaction = function (...args) {
        const transaction = window.originalTransaction.apply(this, args);
        window.transactionQueued = true;
        return transaction;
      };
      window.cancelCreate = new AbortController();
      window.createOutcome = undefined;
      window.storageHost.instance
        .create(input, {
          requestId: 'native-abort',
          signal: window.cancelCreate.signal,
        })
        .then(
          () => {
            window.createOutcome = 'saved';
          },
          error => {
            window.createOutcome = error.name;
          },
        );
    }, old);
    await bobPage.waitForFunction(() => window.transactionQueued);
    await bobPage.evaluate(() => {
      IDBDatabase.prototype.transaction = window.originalTransaction;
    });
    assert.equal(await bobPage.evaluate(() => window.createOutcome), undefined);
    await bobPage.evaluate(() => window.cancelCreate.abort());
    await bobPage.waitForFunction(() => window.createOutcome === 'AbortError');
  } finally {
    await page.evaluate(async () => {
      window.releaseTransaction();
      await window.heldTransaction;
    });
  }
  const after = await page.evaluate(() =>
    window.storageHost.instance.list('sales-orders'),
  );
  assert.equal(after.instances.length, fixture.instances.instances.length + 1);
  const aborted = await bobPage.evaluate(async input => {
    const controller = new AbortController();
    controller.abort();
    try {
      await window.storageHost.instance.create(input, {
        requestId: 'already-aborted',
        signal: controller.signal,
      });
    } catch (error) {
      return error.name;
    }
  }, old);
  assert.equal(aborted, 'AbortError');

  // Reset is durable: the other tab must see fresh seeds and no prior create receipt.
  await page.evaluate(() => window.storageHost.reset());
  const reset = await bobPage.evaluate(() =>
    window.storageHost.instance.list('sales-orders'),
  );
  assert.equal(reset.instances.length, fixture.instances.instances.length);
  assert.notEqual(
    reset.instances.find(row => row.id === 'my-orders').revision,
    old.revision,
  );
  const afterReset = await bobPage.evaluate(
    input =>
      window.storageHost.instance.create(input, { requestId: 'same-create' }),
    old,
  );
  assert.notEqual(afterReset.id, created[0].id);
  console.log(
    'IndexedDB passed: 50 cross-tab CAS races, create receipts, post-write rollback, queued/pre-abort cancellation and durable reset.',
  );
}
