-- Copyright 2026 The SuperPlayer Authors
-- SPDX-License-Identifier: Apache-2.0
--
-- Every startup of @PACKAGE@ in the trace, as Perfetto's stdlib finds it, with the time to initial and
-- full display and the demo's `bindApplication` — the slice an `Application.onCreate` runs inside.
--
-- What this can conclude: how many startups the stdlib module found, of what type, and how long each
-- took by its own definition (`dur`: the platform's launch event to the launch being reported
-- finished; TTID: to the end of the first `DrawFrame`). What it cannot: whether the module found every
-- startup there was. That is checked against the launches the scenario counted, beside it in the report.
--
-- Times are in milliseconds, to one decimal, and empty where the module found none (TTFD needs the
-- demo's `reportFullyDrawn`).
--
-- ref: https://perfetto.dev/docs/analysis/stdlib-docs#android-startup-startups
INCLUDE PERFETTO MODULE android.startup.startups;
INCLUDE PERFETTO MODULE android.startup.time_to_display;

SELECT
  s.startup_id,
  s.startup_type AS type,
  printf('%.1f', s.dur / 1e6) AS dur_ms,
  printf('%.1f', d.time_to_initial_display / 1e6) AS ttid_ms,
  printf('%.1f', d.time_to_full_display / 1e6) AS ttfd_ms,
  (
    SELECT printf('%.1f', max(b.dur) / 1e6)
    FROM thread_slice AS b
    WHERE b.name = 'bindApplication'
      AND b.process_name = '@PACKAGE@'
      AND b.ts BETWEEN s.ts AND s.ts_end
  ) AS bind_application_ms
FROM android_startups AS s
LEFT JOIN android_startup_time_to_display AS d USING (startup_id)
WHERE s.package = '@PACKAGE@'
ORDER BY s.ts;
