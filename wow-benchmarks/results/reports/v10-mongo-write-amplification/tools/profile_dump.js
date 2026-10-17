// Prints the profiled operations since `since` (ms epoch) until `until`, grouped by namespace, op and command name.
const dbs = db.adminCommand({listDatabases: 1}).databases.map(d => d.name).filter(n => !['admin', 'local', 'config'].includes(n));
for (const name of dbs) {
  const d = db.getSiblingDB(name);
  if (!d.getCollectionNames().includes('system.profile')) continue;
  const rows = d.system.profile.aggregate([
    {$match: {ts: {$gte: new Date(since), $lt: new Date(until)}, ns: {$not: /system\.profile/}}},
    {$project: {
      ns: 1, op: 1,
      name: {$cond: [{$eq: ['$op', 'command']}, {$arrayElemAt: [{$map: {input: {$objectToArray: '$command'}, in: '$$this.k'}}, 0]}, '$op']},
      txn: {$cond: [{$ifNull: ['$command.txnNumber', false]}, true, false]},
      docsExamined: 1, nreturned: 1, keysInserted: 1, nMatched: 1}},
    {$group: {_id: {ns: '$ns', op: '$op', name: '$name', txn: '$txn'}, count: {$sum: 1}, docsExamined: {$sum: '$docsExamined'}, nreturned: {$sum: '$nreturned'}, nMatched: {$sum: '$nMatched'}}},
    {$sort: {count: -1}}]).toArray();
  rows.forEach(r => print(JSON.stringify(Object.assign({db: name}, r._id, {count: r.count, docsExamined: r.docsExamined, nreturned: r.nreturned, nMatched: r.nMatched}))));
}
