-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- The demo's main thread over the whole trace: how long it spent in each scheduler state, and how long
-- it slept while inside a slice.
--
-- The main thread is the thread whose tid is the process's pid. Its states come from `thread_state`,
-- which is built from the scheduler events: Running, R (runnable, waiting for a CPU), S (sleeping) and
-- D (uninterruptible, usually I/O). A main thread idles in S too, waiting in its looper for the next
-- message, so S alone says nothing. S *inside a slice* does: the thread was in the middle of something
-- it had traced — a frame, an input event, a binder call — and stopped to wait. That is
-- `sleeping_in_slice_ms`, the time of S that overlaps the thread's top-level slices; `blocked.sql` lists
-- the long ones.
--
-- What this can conclude: where the main thread's time went, and how much of it was spent waiting in
-- the middle of work. What it cannot: what it waited for. A binder reply, a lock and `Thread.sleep` are
-- all S.
--
-- ref: https://perfetto.dev/docs/data-sources/cpu-scheduling
WITH main AS (
  SELECT t.utid
  FROM thread AS t
  JOIN process AS p USING (upid)
  WHERE p.name = '@PACKAGE@' AND t.tid = p.pid
),
states AS (
  SELECT ts.ts, ts.dur, ts.state
  FROM thread_state AS ts
  WHERE ts.utid IN (SELECT utid FROM main) AND ts.dur > 0
),
tops AS (
  SELECT s.ts, s.dur
  FROM slice AS s
  JOIN thread_track AS tt ON s.track_id = tt.id
  WHERE tt.utid IN (SELECT utid FROM main) AND s.depth = 0 AND s.dur > 0
)
SELECT
  printf('%.1f', (SELECT sum(dur) FROM states WHERE state = 'Running') / 1e6) AS running_ms,
  printf('%.1f', (SELECT sum(dur) FROM states WHERE state = 'R' OR state = 'R+') / 1e6) AS runnable_ms,
  printf('%.1f', (SELECT sum(dur) FROM states WHERE state = 'S') / 1e6) AS sleeping_ms,
  printf('%.1f', (SELECT sum(dur) FROM states WHERE state = 'D' OR state = 'DK') / 1e6) AS uninterruptible_ms,
  printf('%.1f', coalesce((
    SELECT sum(min(st.ts + st.dur, tp.ts + tp.dur) - max(st.ts, tp.ts))
    FROM states AS st
    JOIN tops AS tp ON st.ts < tp.ts + tp.dur AND tp.ts < st.ts + st.dur
    WHERE st.state = 'S'
  ), 0) / 1e6) AS sleeping_in_slice_ms;
