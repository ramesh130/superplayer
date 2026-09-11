-- One heap dump as reachable instances per class: the unit two dumps are compared in.
--
-- Counted: objects reachable from a GC root without passing through the referent of a weak, phantom or
-- finalizer reference — which is what a collection keeps — nor through a reference in
-- known-retentions.tsv (@IGNORED_FIELDS@), which the leak hunt treats as weak because it is a
-- documented retention that is not this code's. So an object that was already garbage when the dump
-- was taken is not counted, whether or not a GC had yet run. With no known retentions this is exactly
-- trace processor's own `reachable` flag: on the first dump it was written against, the walk below
-- and that flag marked the same 366,929 objects.
--
-- `is_activity` marks android.app.Activity and every subclass of it: the one kind of object whose
-- live count the platform will state independently (`dumpsys activity`), and so the one whose excess
-- is a leak without needing a baseline.
--
-- Grouped by name rather than by class id: a class loaded by two loaders is two ids with one name, and
-- a report that split them would show one class twice.
--
-- ref: https://perfetto.dev/docs/analysis/sql-tables#heap_graph_object
INCLUDE PERFETTO MODULE graphs.search;
INCLUDE PERFETTO MODULE android.memory.heap_graph.excluded_refs;

CREATE PERFETTO TABLE _hist_edges AS
SELECT owner_id AS source_node_id, owned_id AS dest_node_id
FROM heap_graph_reference
WHERE owned_id IS NOT NULL
  AND id NOT IN (SELECT id FROM _excluded_refs)
  AND field_name NOT IN (@IGNORED_FIELDS@);

CREATE PERFETTO TABLE _hist_roots AS
SELECT id AS node_id FROM heap_graph_object WHERE root_type IS NOT NULL;

CREATE PERFETTO TABLE _hist_kept AS
SELECT node_id AS id FROM graph_reachable_bfs!(_hist_edges, _hist_roots);

WITH RECURSIVE activity_class(id) AS (
  SELECT id FROM heap_graph_class WHERE name = 'android.app.Activity'
  UNION
  SELECT c.id FROM heap_graph_class c JOIN activity_class a ON c.superclass_id = a.id
)
SELECT
  c.name AS class,
  COUNT(*) AS instances,
  SUM(o.self_size) AS self_bytes,
  MAX(c.id IN (SELECT id FROM activity_class)) AS is_activity
FROM _hist_kept k
JOIN heap_graph_object o ON o.id = k.id
JOIN heap_graph_class c ON c.id = o.type_id
GROUP BY c.name
ORDER BY c.name;
