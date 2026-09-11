-- What heapprofd saw over its window: native bytes allocated and not freed by the end of it, and
-- whether the profile it came from is whole.
--
-- heapprofd samples: an allocation is recorded with a probability proportional to its size (the
-- fragment's `sampling_interval_bytes`), and every figure here is an estimate scaled back up from
-- those samples. Allocations made before heapprofd attached are invisible to it, which is why a window
-- started at the baseline measures native growth over the workload and nothing before it.
--
-- ref: https://perfetto.dev/docs/data-sources/native-heap-profiler
SELECT
  (SELECT COALESCE(SUM(size), 0) FROM heap_profile_allocation) AS unreleased_bytes,
  (SELECT COALESCE(SUM(size), 0) FROM heap_profile_allocation WHERE size > 0) AS allocated_bytes,
  (SELECT COUNT(*) FROM (
     SELECT callsite_id FROM heap_profile_allocation GROUP BY callsite_id HAVING SUM(size) > 0
   )) AS callsites,
  (SELECT COALESCE(GROUP_CONCAT(name || '=' || value, ' '), '')
   FROM stats WHERE name LIKE 'heapprofd%' AND value > 0 AND severity IN ('error', 'data_loss')) AS problems;
