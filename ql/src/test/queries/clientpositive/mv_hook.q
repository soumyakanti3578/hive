set hive.support.concurrency=true;
set hive.txn.manager=org.apache.hadoop.hive.ql.lockmgr.DbTxnManager;
set hive.strict.checks.cartesian.product=false;
set hive.materializedview.rewriting=true;

CREATE TABLE tbl1(name string, id int) stored as orc tblproperties("transactional"="true");
CREATE MATERIALIZED VIEW tbl1_view as select * from tbl1;
