/*
 * superplayer-core — SuperPlayer facade, PlaybackSession, config profiles, player pool
 *
 * The one module a consumer depends on, and so far the only one carrying library code. It holds
 * `SuperPlayer` — a Media3 `Player` built by `SuperPlayer.Builder` — along with the types that
 * travel through it: `MediaRequest`, `PlaybackProfile`, `PlaybackSnapshot`, `PlaybackPolicy`,
 * `PlaybackSession`, `PlaybackService` and `PlayerPool`. Everything public here is tracked in
 * `api/superplayer-core.api` and enforced on every build.
 *
 * For what the module is for and how it fits the others, read `CLAUDE.md` and `docs/modules.md`
 * rather than a third copy of them here.
 */
package com.superplayer.core
