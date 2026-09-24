-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- Every frame of @PACKAGE@'s window in the trace, from SurfaceFlinger's frame timeline: how many, how
-- many were janky, and the distribution of how long each took.
--
-- A frame is a row of `actual_frame_timeline_slice` on the demo's window layer: one frame of its UI, from
-- the app starting it to its present. The demo's `SurfaceView` layers are left out. Each is a player's
-- video, whose buffers the decoder queues at the content's rate whether or not the UI draws anything, so
-- counting them would count video frames as UI frames.
--
-- Two kinds of jank, because on the emulator they differ by a factor of three or more:
--   jank_pct       any `jank_type` other than `None`. On the API 36 emulator nearly every frame is janky
--                  this way, mostly `Buffer Stuffing` and `Prediction Error`: the emulator's display
--                  pipeline, the same for every build, and so no signal.
--   app_jank_pct   a `jank_type` naming `App Deadline Missed`: the app itself took too long.
-- Percentiles are by nearest rank over the frames' `dur`, in milliseconds, so every one is a frame that
-- happened. `dur` runs to the frame's present, so it includes time queued behind SurfaceFlinger too; a
-- frame with no present yet when the trace stopped has none, and is left out.
--
-- What this can conclude: how the demo's frames met their deadlines over the whole trace. What it
-- cannot: which gesture a frame belonged to, or why it was late; `blocked.sql` and `gc.sql` are two of
-- the reasons, and `frame_work.sql` is the app's side of each frame.
--
-- ref: https://perfetto.dev/docs/data-sources/frametimeline
-- ref: https://en.wikipedia.org/wiki/Percentile#The_nearest-rank_method
WITH frames AS (
  SELECT a.dur, a.jank_type
  FROM actual_frame_timeline_slice AS a
  JOIN process AS p USING (upid)
  WHERE p.name = '@PACKAGE@' AND a.layer_name NOT GLOB '*SurfaceView*' AND a.dur > 0
),
ranked AS (
  SELECT dur, row_number() OVER (ORDER BY dur) AS rank, count() OVER () AS n
  FROM frames
)
SELECT
  (SELECT count() FROM frames) AS frames,
  (SELECT printf('%.1f', 100.0 * count() / max(1, (SELECT count() FROM frames)))
     FROM frames WHERE jank_type != 'None') AS jank_pct,
  (SELECT printf('%.1f', 100.0 * count() / max(1, (SELECT count() FROM frames)))
     FROM frames WHERE jank_type GLOB '*App Deadline Missed*') AS app_jank_pct,
  (SELECT printf('%.1f', dur / 1e6) FROM ranked WHERE rank = max(1, (n * 50 + 99) / 100)) AS p50_ms,
  (SELECT printf('%.1f', dur / 1e6) FROM ranked WHERE rank = max(1, (n * 95 + 99) / 100)) AS p95_ms,
  (SELECT printf('%.1f', dur / 1e6) FROM ranked WHERE rank = max(1, (n * 99 + 99) / 100)) AS p99_ms,
  (SELECT printf('%.1f', max(dur) / 1e6) FROM frames) AS max_ms;
