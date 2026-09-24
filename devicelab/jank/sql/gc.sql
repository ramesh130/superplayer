-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- The demo's garbage collections, by kind: how many, and how long they took in all.
--
-- ART traces each collection under atrace's `dalvik` category as a slice named for the collector, on the
-- `HeapTaskDaemon` thread that runs it (`young concurrent copying GC`, `concurrent copying GC`, …). A
-- concurrent collection runs beside the app's threads rather than stopping them, so its time is not the
-- time the app was paused: that is its short pauses, which are part of the same slices' children.
--
-- What this can conclude: how often the demo collected and how long the collector ran. What it cannot:
-- what was allocated.
--
-- ref: https://perfetto.dev/docs/data-sources/atrace
-- ref: https://source.android.com/docs/core/runtime/gc-debug
SELECT
  s.name AS collection,
  count() AS count,
  printf('%.1f', sum(s.dur) / 1e6) AS total_ms,
  printf('%.1f', max(s.dur) / 1e6) AS longest_ms
FROM slice AS s
JOIN thread_track AS tt ON s.track_id = tt.id
JOIN thread AS t USING (utid)
JOIN process AS p USING (upid)
WHERE p.name = '@PACKAGE@'
  AND s.name GLOB '*GC*'
  AND s.depth = 0
GROUP BY s.name
ORDER BY sum(s.dur) DESC;
