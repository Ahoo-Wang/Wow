// One step of the layout experiment, run by mongosh with LAYOUT (A, B or C), and either SETUP=1 (create the
// collection and its indexes) or WORKER and WORKERS (insert this worker's partitions' share).
// A: an ObjectId _id, unique (aggregate, tenant, version), (tenant, request) and (partition, sequence).
// B: the log position as a long _id, the first two unique indexes. C: B in a collection clustered by _id.
const layout = process.env.LAYOUT, N = Number(process.env.N || 400000), BATCH = Number(process.env.BATCH || 64), PARTITIONS = 64, ACCOUNTS = 100000;
const dbx = db.getSiblingDB('layouts');
const name = 'log_' + layout;
if (process.env.SETUP) {
  dbx.getCollection(name).drop();
  if (layout === 'C') dbx.createCollection(name, { clusteredIndex: { key: { _id: 1 }, unique: true } });
  else dbx.createCollection(name);
  const c = dbx.getCollection(name);
  c.createIndex({ aggregate_id: 1, tenant_id: 1, version: 1 }, { unique: true, partialFilterExpression: { version: { $exists: true } } });
  c.createIndex({ tenant_id: 1, request_id: 1 }, { unique: true, partialFilterExpression: { request_id: { $exists: true } } });
  if (layout === 'A') c.createIndex({ partition: 1, sequence: 1 }, { unique: true });
  quit();
}
const worker = Number(process.env.WORKER), workers = Number(process.env.WORKERS);
const c = dbx.getCollection(name);
// Worker w writes the partitions p with p % workers == w, each its next sequences, in batches of one partition.
let i = worker;
const sequences = {};
let docs = [];
let partitionOfBatch = -1;
for (let n = 0; n < N / workers; n++, i += workers) {
  const p = i % PARTITIONS;
  const seq = (sequences[p] = (sequences[p] || 0) + 1);
  const d = {
    partition: p, sequence: NumberLong(String(seq)), tenant_id: '(0)', aggregate_id: 'account-' + (i % ACCOUNTS),
    version: Math.floor(i / ACCOUNTS) + 1, command_id: 'c-' + i, request_id: 'r-' + i,
    command_type: 'transfer.account.Deposit', commit_time: NumberLong(String(Date.now())),
    events: [{ name: 'transfer.account.Deposited', revision: 1, payload: { amount: NumberLong(String(i % 1000)) } }],
    owner_id: '', space_id: '', causation: null, headers: {},
  };
  if (layout !== 'A') d._id = NumberLong(((BigInt(p) << 51n) | BigInt(seq)).toString());
  docs.push(d);
  if (docs.length === BATCH) {
    c.insertMany(docs, { ordered: true, writeConcern: { w: 'majority' } });
    docs = [];
  }
}
if (docs.length) c.insertMany(docs, { ordered: true, writeConcern: { w: 'majority' } });
