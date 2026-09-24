-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- The app's own side of each frame: how long @PACKAGE@'s main thread spent in each
-- `Choreographer#doFrame` — input, animation, measure, layout and recording the draw — and its
-- RenderThread in each `DrawFrame`. Counts, and percentiles by nearest rank, in milliseconds.
--
-- Beside `frames.sql` because the timeline's `dur` runs to the present and so carries SurfaceFlinger's
-- queueing as well, which on the emulator is most of it. These two are only the app's work, and a
-- regression in what the app does per frame shows here first.
--
-- What this can conclude: how long the app worked on its frames. What it cannot: whether a frame was
-- presented late; that is the timeline's.
--
-- ref: https://perfetto.dev/docs/data-sources/frametimeline
-- ref: https://en.wikipedia.org/wiki/Percentile#The_nearest-rank_method
WITH work AS (
  SELECT
    CASE WHEN t.tid = p.pid THEN 'Choreographer#doFrame' ELSE 'DrawFrame' END AS slice,
    s.dur
  FROM slice AS s
  JOIN thread_track AS tt ON s.track_id = tt.id
  JOIN thread AS t USING (utid)
  JOIN process AS p USING (upid)
  WHERE p.name = '@PACKAGE@'
    AND s.depth = 0
    AND (
      (t.tid = p.pid AND s.name GLOB 'Choreographer#doFrame *')
      OR (t.name = 'RenderThread' AND s.name GLOB 'DrawFrame*')
    )
),
ranked AS (
  SELECT slice, dur, row_number() OVER (PARTITION BY slice ORDER BY dur) AS rank,
    count() OVER (PARTITION BY slice) AS n
  FROM work
)
SELECT
  slice,
  max(n) AS count,
  printf('%.1f', max(CASE WHEN rank = max(1, (n * 50 + 99) / 100) THEN dur END) / 1e6) AS p50_ms,
  printf('%.1f', max(CASE WHEN rank = max(1, (n * 95 + 99) / 100) THEN dur END) / 1e6) AS p95_ms,
  printf('%.1f', max(CASE WHEN rank = max(1, (n * 99 + 99) / 100) THEN dur END) / 1e6) AS p99_ms,
  printf('%.1f', max(dur) / 1e6) AS max_ms
FROM ranked
GROUP BY slice
ORDER BY slice;
