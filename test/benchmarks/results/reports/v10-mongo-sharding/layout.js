// One step of the D277 layout probe, run by mongosh with LAYOUT and STEP (setup, insert, update, read), and WORKER and
// WORKERS for the steps that write or read. Log layouts: L0, main's (the position as _id, unique (aggregate, tenant,
// version) and (tenant, request)); L1, D277's (both keys led by the partition). Snapshot layouts: S0, main's (an
// ObjectId _id, unique (tenant, aggregate), the two partial scope indexes); S1, D277's (_id {t, a}, the scope
// indexes alone). Each write is the store's: a log insertMany of 64 of one partition, a snapshot upsert guarded by
// its version.
const layout = process.env.LAYOUT, step = process.env.STEP, N = Number(process.env.N || 400000);
const PARTITIONS = 64, ACCOUNTS = Number(process.env.ACCOUNTS || 200000), BATCH = 64;
const dbx = db.getSiblingDB('d277');
const c = dbx.getCollection('c_' + layout);
const log = layout.startsWith('L');
const worker = Number(process.env.WORKER || 0), workers = Number(process.env.WORKERS || 1);
const tenant = (i) => 't' + (i % 10);
const id = (i) => 'account-' + i;
// A stand-in for the partitioner: the aggregate's number, so a worker's aggregates spread over the partitions.
const partitionOf = (i) => i % PARTITIONS;
if (step === 'setup') {
  c.drop();
  dbx.createCollection(c.getName());
  const versions = { partialFilterExpression: { version: { $exists: true } }, unique: true };
  const requests = { partialFilterExpression: { request_id: { $exists: true } }, unique: true };
  const scope = { partialFilterExpression: { deleted: false } };
  if (layout === 'L0') {
    c.createIndex({ aggregate_id: 1, tenant_id: 1, version: 1 }, versions);
    c.createIndex({ tenant_id: 1, request_id: 1 }, requests);
  } else if (layout === 'L1') {
    c.createIndex({ partition: 1, aggregate_id: 1, tenant_id: 1, version: 1 }, versions);
    c.createIndex({ partition: 1, tenant_id: 1, request_id: 1 }, requests);
  } else {
    if (layout === 'S0') c.createIndex({ tenant_id: 1, aggregate_id: 1 }, { unique: true });
    c.createIndex({ tenant_id: 1, owner_id: 1 }, scope);
    c.createIndex({ tenant_id: 1, space_id: 1 }, scope);
  }
  quit();
}
const keyOf = (i) => layout === 'S1' ? { _id: { t: tenant(i), a: id(i) } } : {};
if (log && step === 'insert') {
  // Worker w writes the partitions p with p % workers == w, each its next sequences, a batch a partition.
  const sequences = {};
  for (let p = worker; p < PARTITIONS; p += workers) {
    let docs = [];
    for (let i = p; i < N; i += PARTITIONS) {
      const seq = (sequences[p] = (sequences[p] || 0) + 1);
      docs.push({
        _id: NumberLong(((BigInt(p) << 51n) | BigInt(seq)).toString()), partition: p, sequence: NumberLong(String(seq)),
        tenant_id: tenant(i), aggregate_id: id(i % ACCOUNTS), version: Math.floor(i / ACCOUNTS) + 1,
        command_id: 'c-' + i, request_id: 'r-' + i, command_type: 'transfer.account.Deposit',
        commit_time: NumberLong(String(Date.now())),
        events: [{ name: 'transfer.account.Deposited', revision: 1, payload: { amount: NumberLong(String(i % 1000)) } }],
        owner_id: '', space_id: '', headers: {},
      });
      if (docs.length === BATCH) { c.insertMany(docs, { ordered: true, writeConcern: { w: 'majority' } }); docs = []; }
    }
    if (docs.length) c.insertMany(docs, { ordered: true, writeConcern: { w: 'majority' } });
  }
  quit();
}
if (log && step === 'read') {
  // The latest stream of each of this worker's aggregates, by the version key, as the log reads one.
  for (let i = worker; i < ACCOUNTS; i += workers) {
    const filter = layout === 'L1'
      ? { partition: partitionOf(i), aggregate_id: id(i), tenant_id: tenant(i), version: { $exists: true } }
      : { aggregate_id: id(i), tenant_id: tenant(i), version: { $exists: true } };
    c.find(filter).sort({ version: -1 }).limit(1).readConcern('majority').toArray();
  }
  quit();
}
// Snapshots: insert (version 1 of new aggregates), update (version 2 over them), read (a load by key).
for (let i = worker; i < ACCOUNTS; i += workers) {
  const key = Object.assign(keyOf(i), { tenant_id: tenant(i), aggregate_id: id(i) });
  if (step === 'read') {
    c.findOne(key);
    continue;
  }
  const version = step === 'insert' ? 1 : 2;
  c.updateOne(
    Object.assign({}, key, { version: { $lt: version } }),
    { $set: { version: version, schema_fingerprint: 'f', state: { balance: NumberLong(String(i % 1000)), name: 'n' + i },
      owner_id: 'owner-' + (i % 500), space_id: 'space-' + (i % 300), deleted: false,
      first_event_time: NumberLong('1'), last_event_time: NumberLong(String(Date.now())), tags: {} } },
    { upsert: true, writeConcern: { w: 'majority' } },
  );
}
