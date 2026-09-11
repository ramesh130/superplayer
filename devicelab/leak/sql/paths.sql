-- For each class in @CLASSES@, its instances' most common shortest retaining paths from a GC root,
-- one row per hop: the object at the hop, and the field of the previous hop's object that points to it.
--
-- A path is the answer to "what is retaining this", in the form a reader can act on: at every hop the
-- field is named with its declaring class, which is a file and a line to go and read. It is not an
-- allocation site. A Java heap graph records references, not where an object was created, so the
-- place in code this names is the field that holds the reference — which for a retention bug is the
-- defect — and never the line that allocated it.
--
-- The graph walked is the strong one: weak, phantom and finalizer referents are removed with stdlib's
-- own `_excluded_refs`, since those do not keep anything alive, and so are the known retentions
-- (@IGNORED_FIELDS@), exactly as histogram.sql counts. The walk is breadth first from every
-- root at once, so each object's parent is the previous hop of one of its *shortest* paths. That is
-- one path of possibly several; it is a real one, and it is the shortest, which is the one a fix has to
-- break first. Instances are grouped by the shape of their path (the classes and fields along it), so
-- 400 listeners leaked the same way are one path shared by 400 rather than 400 paths.
--
-- ref: https://perfetto.dev/docs/analysis/stdlib-docs#graphs-search
-- ref: https://perfetto.dev/docs/analysis/stdlib-docs#android-memory-heap_graph-excluded_refs
INCLUDE PERFETTO MODULE graphs.search;
INCLUDE PERFETTO MODULE android.memory.heap_graph.excluded_refs;

CREATE PERFETTO TABLE _leak_edges AS
SELECT owner_id AS source_node_id, owned_id AS dest_node_id
FROM heap_graph_reference
WHERE owned_id IS NOT NULL
  AND id NOT IN (SELECT id FROM _excluded_refs)
  AND field_name NOT IN (@IGNORED_FIELDS@);

CREATE PERFETTO TABLE _leak_roots AS
SELECT id AS node_id FROM heap_graph_object WHERE root_type IS NOT NULL;

CREATE PERFETTO TABLE _leak_tree AS
SELECT node_id, parent_node_id FROM graph_reachable_bfs!(_leak_edges, _leak_roots);

-- Indexed, because the walk below looks a parent up once per hop per instance. Without it each
-- lookup scans the whole tree: harmless for two instances, and ten minutes for the few thousand
-- buffer objects a feed's players hold.
CREATE PERFETTO INDEX _leak_tree_node ON _leak_tree(node_id);

CREATE PERFETTO TABLE _leak_targets AS
SELECT o.id, c.name AS class
FROM heap_graph_object o
JOIN heap_graph_class c ON c.id = o.type_id
WHERE o.reachable AND c.name IN (@CLASSES@);

-- Each target walked up to its root: depth 0 is the target itself, the largest depth the root.
CREATE PERFETTO TABLE _leak_walk AS
WITH RECURSIVE up(target, node, depth) AS (
  SELECT id, id, 0 FROM _leak_targets
  UNION ALL
  SELECT up.target, t.parent_node_id, up.depth + 1
  FROM up
  JOIN _leak_tree t ON t.node_id = up.node
  WHERE t.parent_node_id IS NOT NULL AND up.depth < 200
)
SELECT target, node, depth FROM up;

CREATE PERFETTO INDEX _leak_walk_step ON _leak_walk(target, depth);

-- The field on each step, looked up once per distinct (parent, child) pair rather than per target,
-- and through the parent's reference set, which is how stdlib's own heap graph modules reach an
-- object's references: a range of the table rather than a scan of all of it.
CREATE PERFETTO TABLE _leak_pairs AS
SELECT DISTINCT p.node AS parent, c.node AS child
FROM _leak_walk c
JOIN _leak_walk p ON p.target = c.target AND p.depth = c.depth + 1;

CREATE PERFETTO TABLE _leak_fields AS
SELECT pr.parent, pr.child, MIN(r.field_name) AS field
FROM _leak_pairs pr
JOIN heap_graph_object o ON o.id = pr.parent
JOIN heap_graph_reference r ON r.reference_set_id = o.reference_set_id AND r.owned_id = pr.child
GROUP BY pr.parent, pr.child;

CREATE PERFETTO INDEX _leak_fields_step ON _leak_fields(parent, child);

CREATE PERFETTO TABLE _leak_hops AS
SELECT
  w.target,
  t.class,
  w.depth,
  MAX(w.depth) OVER (PARTITION BY w.target) - w.depth AS hop,
  oc.name AS object_class,
  COALESCE(f.field, '') AS via_field,
  COALESCE(o.root_type, '') AS root_type
FROM _leak_walk w
JOIN _leak_targets t ON t.id = w.target
JOIN heap_graph_object o ON o.id = w.node
JOIN heap_graph_class oc ON oc.id = o.type_id
LEFT JOIN _leak_walk p ON p.target = w.target AND p.depth = w.depth + 1
LEFT JOIN _leak_fields f ON f.parent = p.node AND f.child = w.node;

CREATE PERFETTO TABLE _leak_signatures AS
SELECT target, class, GROUP_CONCAT(object_class || '<' || via_field, '>' ORDER BY hop) AS signature
FROM _leak_hops
GROUP BY target, class;

CREATE PERFETTO TABLE _leak_paths AS
SELECT
  class,
  signature,
  COUNT(*) AS instances,
  MIN(target) AS example,
  ROW_NUMBER() OVER (PARTITION BY class ORDER BY COUNT(*) DESC, MIN(target)) AS path_rank
FROM _leak_signatures
GROUP BY class, signature;

SELECT
  p.class,
  (SELECT COUNT(*) FROM _leak_targets t WHERE t.class = p.class) AS total,
  p.path_rank,
  p.instances AS path_instances,
  h.hop,
  h.object_class,
  h.via_field,
  h.root_type
FROM _leak_paths p
JOIN _leak_hops h ON h.target = p.example
WHERE p.path_rank <= @PATHS_PER_CLASS@
ORDER BY p.class, p.path_rank, h.hop;
