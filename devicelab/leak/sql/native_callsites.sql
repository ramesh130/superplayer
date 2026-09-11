-- The @TOP@ callsites holding the most native memory at the end of heapprofd's window, each with its
-- callstack from the allocating frame outward.
--
-- A callstack is the half of this report that maps to an allocation *site*: a native leak has no
-- object to point at, so where it was allocated is the whole of what can be said. Frames name a
-- function and the library it is in. They name a file and a line only if the library was symbolized
-- with its unstripped binary, which nothing here does: system libraries on a device image are
-- stripped to their dynamic symbols. C++ names are demangled; a frame with no symbol at all is
-- written as its library offset.
--
-- ref: https://perfetto.dev/docs/data-sources/native-heap-profiler#heapprofd-vs-malloc_info
-- ref: https://perfetto.dev/docs/analysis/sql-tables#stack_profile_callsite
CREATE PERFETTO TABLE _native_top AS
SELECT
  callsite_id,
  SUM(size) AS bytes,
  SUM(count) AS allocations,
  ROW_NUMBER() OVER (ORDER BY SUM(size) DESC, callsite_id) AS rank
FROM heap_profile_allocation
GROUP BY callsite_id
HAVING SUM(size) > 0;

CREATE PERFETTO TABLE _native_frames AS
WITH RECURSIVE stack(rank, callsite, depth) AS (
  SELECT rank, callsite_id, 0 FROM _native_top WHERE rank <= @TOP@
  UNION ALL
  SELECT s.rank, c.parent_id, s.depth + 1
  FROM stack s
  JOIN stack_profile_callsite c ON c.id = s.callsite
  WHERE c.parent_id IS NOT NULL AND s.depth < @FRAMES@ - 1
)
SELECT
  s.rank,
  s.depth,
  COALESCE(f.deobfuscated_name, DEMANGLE(f.name), f.name, printf('0x%x', f.rel_pc)) AS frame,
  COALESCE(m.name, '') AS mapping
FROM stack s
JOIN stack_profile_callsite c ON c.id = s.callsite
JOIN stack_profile_frame f ON f.id = c.frame_id
LEFT JOIN stack_profile_mapping m ON m.id = f.mapping;

SELECT t.rank, t.bytes AS unreleased_bytes, t.allocations, fr.depth, fr.frame, fr.mapping
FROM _native_top t
JOIN _native_frames fr ON fr.rank = t.rank
ORDER BY t.rank, fr.depth;
