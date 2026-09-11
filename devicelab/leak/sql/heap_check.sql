-- Whether a heap trace holds exactly one finished heap dump of one process, and of what.
--
-- A java_hprof dump is written by a fork of the app after the data source starts. A trace stopped
-- before that fork finished holds a partial graph, which trace processor flags as non-finalized
-- rather than refusing, and which would read as a heap with most of its objects missing.
--
-- ref: https://perfetto.dev/docs/data-sources/java-heap-profiler
SELECT
  (SELECT COUNT(*) FROM heap_graph_object) AS objects,
  (SELECT COUNT(*) FROM heap_graph_object WHERE reachable) AS reachable,
  (SELECT COUNT(DISTINCT graph_sample_ts) FROM heap_graph_object) AS dumps,
  (SELECT COUNT(DISTINCT upid) FROM heap_graph_object) AS processes,
  (SELECT COALESCE(SUM(value), 0) FROM stats WHERE name = 'heap_graph_non_finalized_graph') AS unfinished,
  (SELECT COALESCE(GROUP_CONCAT(DISTINCT p.name), '') FROM heap_graph_object o JOIN process p USING (upid)) AS process;
