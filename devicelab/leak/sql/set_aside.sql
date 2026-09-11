-- What the known retentions set aside in one heap dump: objects trace processor counts as reachable
-- that are reachable *only* through a reference in known-retentions.tsv (@IGNORED_FIELDS@), by class,
-- most first.
--
-- histogram.sql leaves exactly these out, which is how an entry keeps a retention that is not this
-- code's out of the verdict. This is what makes that visible rather than silent: the report prints the
-- total at both dumps, so an entry that starts hiding more than it did is seen growing.
INCLUDE PERFETTO MODULE graphs.search;
INCLUDE PERFETTO MODULE android.memory.heap_graph.excluded_refs;

CREATE PERFETTO TABLE _aside_edges AS
SELECT owner_id AS source_node_id, owned_id AS dest_node_id
FROM heap_graph_reference
WHERE owned_id IS NOT NULL
  AND id NOT IN (SELECT id FROM _excluded_refs)
  AND field_name NOT IN (@IGNORED_FIELDS@);

CREATE PERFETTO TABLE _aside_roots AS
SELECT id AS node_id FROM heap_graph_object WHERE root_type IS NOT NULL;

CREATE PERFETTO TABLE _aside_kept AS
SELECT node_id AS id FROM graph_reachable_bfs!(_aside_edges, _aside_roots);

SELECT c.name AS class, COUNT(*) AS instances
FROM heap_graph_object o
JOIN heap_graph_class c ON c.id = o.type_id
WHERE o.reachable AND o.id NOT IN (SELECT id FROM _aside_kept)
GROUP BY c.name
ORDER BY instances DESC, class;
