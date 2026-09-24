-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- Where the demo's main thread slept in the middle of work: `main_thread.sql`'s `sleeping_in_slice_ms`,
-- broken down by the innermost slice each sleep began in, the ten with the most sleep.
--
-- The innermost slice is what the thread was doing when it stopped: `postAndWait` is the main thread
-- waiting for the RenderThread to take a frame, which it does in every frame and which on the emulator
-- is most of this table; `AndroidOwner:onTouch` is Compose handing a touch to the composition, which is
-- where a click handler runs. `long_sleeps` counts the sleeps of more than 16 ms, a frame at 60 Hz, each
-- long enough to cost a frame by itself. A `Choreographer#doFrame`'s vsync id is dropped from its name
-- so that its frames group.
--
-- What this can conclude: what the main thread was doing each time it waited, and for how long. What it
-- cannot: what it waited for. A binder reply, a lock and `Thread.sleep` are all S; `blocked_function`
-- is set only for an uninterruptible sleep, which these are not.
--
-- ref: https://perfetto.dev/docs/data-sources/cpu-scheduling
WITH main AS (
  SELECT t.utid
  FROM thread AS t
  JOIN process AS p USING (upid)
  WHERE p.name = '@PACKAGE@' AND t.tid = p.pid
),
sleeps AS (
  SELECT st.id, st.ts, st.dur, st.utid
  FROM thread_state AS st
  WHERE st.utid IN (SELECT utid FROM main) AND st.state = 'S' AND st.dur > 0
),
innermost AS (
  -- Every slice open when the sleep began, deepest first; the first row per sleep is the one it began in.
  SELECT
    sl.id,
    min(sl.ts + sl.dur, s.ts + s.dur) - sl.ts AS dur,
    s.name,
    row_number() OVER (PARTITION BY sl.id ORDER BY s.depth DESC) AS nth
  FROM sleeps AS sl
  JOIN thread_track AS tt ON tt.utid = sl.utid
  JOIN slice AS s ON s.track_id = tt.id AND s.ts <= sl.ts AND sl.ts < s.ts + s.dur
)
SELECT
  CASE WHEN name GLOB 'Choreographer#doFrame *' THEN 'Choreographer#doFrame' ELSE name END AS slice,
  count() AS sleeps,
  sum(dur > 16e6) AS long_sleeps,
  printf('%.1f', sum(dur) / 1e6) AS slept_ms,
  printf('%.1f', max(dur) / 1e6) AS longest_ms
FROM innermost
WHERE nth = 1
GROUP BY 1
ORDER BY sum(dur) DESC
LIMIT 10;
