// The profiled operations since `since` until `until` (ms epoch), grouped by namespace, op, command name, whether in a
// transaction, and the shape of the filter (its top-level field names), one JSON line per group.
const dbs = db.adminCommand({listDatabases: 1}).databases.map(d => d.name).filter(n => !['admin', 'local', 'config'].includes(n));
const filterOf = {$ifNull: ['$command.filter', '$command.query', '$command.q',
  {$getField: {field: {$literal: '$match'}, input: {$arrayElemAt: [{$ifNull: ['$command.pipeline', [{}]]}, 0]}}}, {}]};
for (const name of dbs) {
  const d = db.getSiblingDB(name);
  if (!d.getCollectionNames().includes('system.profile')) continue;
  d.system.profile.aggregate([
    {$match: {ts: {$gte: new Date(since), $lt: new Date(until)}, ns: {$not: /system\.profile/}}},
    {$project: {
      ns: 1, op: 1,
      name: {$cond: [{$eq: ['$op', 'command']}, {$arrayElemAt: [{$map: {input: {$objectToArray: '$command'}, in: '$$this.k'}}, 0]}, '$op']},
      txn: {$cond: [{$ifNull: ['$command.txnNumber', false]}, true, false]},
      shape: {$reduce: {input: {$map: {input: {$objectToArray: {$cond: [{$eq: [{$type: filterOf}, 'object']}, filterOf, {}]}}, in: '$$this.k'}},
        initialValue: '', in: {$concat: ['$$value', {$cond: [{$eq: ['$$value', '']}, '', ',']}, '$$this']}}}}},
    {$group: {_id: {ns: '$ns', op: '$op', name: '$name', txn: '$txn', shape: '$shape'}, count: {$sum: 1}}},
    {$sort: {count: -1}}]).forEach(r => print(JSON.stringify(Object.assign({db: name}, r._id, {count: r.count}))));
}
