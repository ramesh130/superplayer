-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- The demo's garbage collections, by kind: how many, and how long they took in all; then every wait for
-- one, by the thread that waited.
--
-- ART traces each collection under atrace's `dalvik` category as a top-level slice on the demo's
-- `HeapTaskDaemon`, named for the collector (`Background young concurrent mark compact GC`, …). A
-- concurrent collection runs beside the app's threads rather than stopping them, so its time is not the
-- time the app was paused. A thread that needs memory the collector has not freed yet waits for it, in
-- a `GC: Wait For Completion …` slice on that thread: those are the rows that cost the app time directly,
-- and a main thread among them has waited for the collector inside a frame.
--
-- What this can conclude: how often the demo collected, how long the collector ran, and who waited for
-- it. What it cannot: what was allocated.
--
-- ref: https://perfetto.dev/docs/data-sources/atrace
-- ref: https://source.android.com/docs/core/runtime/gc-debug
SELECT
  CASE
    WHEN t.name = 'HeapTaskDaemon' THEN s.name
    WHEN t.tid = p.pid THEN s.name || ' (main thread)'
    ELSE s.name || ' (' || coalesce(t.name, '?') || ')'
  END AS collection,
  count() AS count,
  printf('%.1f', sum(s.dur) / 1e6) AS total_ms,
  printf('%.1f', max(s.dur) / 1e6) AS longest_ms
FROM slice AS s
JOIN thread_track AS tt ON s.track_id = tt.id
JOIN thread AS t USING (utid)
JOIN process AS p USING (upid)
WHERE p.name = '@PACKAGE@'
  AND (
    (t.name = 'HeapTaskDaemon' AND s.depth = 0 AND s.name GLOB '*GC')
    OR s.name GLOB 'GC: Wait For Completion*'
  )
GROUP BY 1
ORDER BY t.name = 'HeapTaskDaemon' DESC, sum(s.dur) DESC;
