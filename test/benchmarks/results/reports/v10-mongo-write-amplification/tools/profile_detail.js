// Details of the profiled operations since `since` until `until`: checkpoint updates by subscription, one sample of
// each (ns, op, name, txn) group, and the lease writes by filter shape.
const dbs = db.adminCommand({listDatabases: 1}).databases.map(d => d.name).filter(n => !['admin', 'local', 'config'].includes(n));
const window = {ts: {$gte: new Date(since), $lt: new Date(until)}};
for (const name of dbs) {
  const d = db.getSiblingDB(name);
  if (!d.getCollectionNames().includes('system.profile')) continue;
  print('== checkpoint updates by subscription');
  d.system.profile.aggregate([
    {$match: Object.assign({op: 'update', ns: /wow_checkpoint$/}, window)},
    {$project: {and: {$getField: {field: {$literal: '$and'}, input: '$command.q'}}}},
    {$project: {key: {$getField: {field: {$literal: '$and'}, input: {$arrayElemAt: ['$and', 0]}}}}},
    {$group: {_id: {$getField: {field: 'subscription', input: {$arrayElemAt: ['$key', 1]}}}, count: {$sum: 1}}}, {$sort: {count: -1}}]).forEach(r => print(JSON.stringify(r)));
  print('== samples');
  d.system.profile.aggregate([
    {$match: Object.assign({ns: {$not: /system\.profile/}}, window)},
    {$group: {_id: {ns: '$ns', op: '$op'}, sample: {$first: '$command'}, count: {$sum: 1}}}, {$sort: {count: -1}}]).forEach(r => {
      const s = r.sample || {};
      delete s.lsid; delete s.$clusterTime; delete s.$db; delete s.$readPreference;
      let text = JSON.stringify(s);
      if (text.length > 1500) text = text.substring(0, 1500) + '...';
      print(r.count + ' ' + JSON.stringify(r._id) + ' ' + text);
    });
}
