// sample.js, run by lease-storm.sh sample on the primary: one line of what the lease store serves now.
const dbn = typeof DBN !== "undefined" ? DBN : "eshop_catalog";
const c = db.serverStatus().connections;
const ops = db.getSiblingDB("admin").aggregate([{$currentOp:{allUsers:true,idleConnections:false}},
  {$match:{ns:{$regex:"\\.wow_lease"}}}]).toArray();
const fam = ops.filter(o => o.command && (o.command.findAndModify || o.command.findandmodify));
const upd = ops.filter(o => o.command && o.command.update);
const secs = fam.map(o => o.secs_running || 0).sort((a,b)=>a-b);
const flow = fam.filter(o => o.waitingForFlowControl).length;
const now = Date.now();
const coll = db.getSiblingDB(dbn).wow_lease;
const live = coll.countDocuments({expires_at:{$gt:now}});
const owners = coll.aggregate([{$match:{expires_at:{$gt:now}}},{$group:{_id:"$owner",n:{$sum:1}}},{$sort:{_id:1}}])
  .toArray().map(o => o._id + ":" + o.n).join(",");
print([new Date().toISOString().substr(11,8), "conn="+c.current+"/"+c.active, "fam="+fam.length, "upd="+upd.length,
  "flow="+flow, "secs.med="+(secs[Math.floor(secs.length/2)]||0), "secs.max="+(secs[secs.length-1]||0),
  "live="+live, owners].join(" "));
