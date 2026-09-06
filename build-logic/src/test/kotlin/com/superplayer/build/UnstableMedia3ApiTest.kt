package com.superplayer.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind `verifyNoUnstableMedia3InPublicApi` (ADR-0001 rule 2).
 *
 * The bytecode reading is separated out behind the `isUnstable` parameter, so these tests are about
 * the rule itself: which types in a dump count as a leak, which are permitted and why, and that the
 * things that merely *look* like a Media3 type are not reported — a check that cries wolf is a check
 * that gets turned off.
 */
class UnstableMedia3ApiTest {

    @Test
    fun `an unstable type in a return position is a leak`() {
        val surface = "\tpublic final fun getLoadControl ()Landroidx/media3/exoplayer/LoadControl;"

        assertEquals(
            listOf("androidx/media3/exoplayer/LoadControl: public final fun getLoadControl ()Landroidx/media3/exoplayer/LoadControl;"),
            findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true }),
        )
    }

    @Test
    fun `an unstable type in a parameter position is a leak`() {
        val surface = "\tpublic final fun setLoadControl (Landroidx/media3/exoplayer/LoadControl;)V"

        assertTrue(
            findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true })
                .single()
                .startsWith("androidx/media3/exoplayer/LoadControl:")
        )
    }

    @Test
    fun `an unstable supertype is a leak`() {
        // The position that actually caught the facade: `SuperPlayer : ForwardingPlayer` put an
        // @UnstableApi type on every method a consumer could reach.
        val surface = "public final class com/superplayer/core/SuperPlayer : androidx/media3/common/ForwardingPlayer {"

        assertTrue(
            findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true })
                .single()
                .startsWith("androidx/media3/common/ForwardingPlayer:")
        )
    }

    @Test
    fun `a stable Media3 type is not a leak`() {
        val surface = "\tpublic final fun getCurrentTracks ()Landroidx/media3/common/Tracks;"

        assertEquals(
            emptyList<String>(),
            findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { false }),
        )
    }

    @Test
    fun `an allowed type is not a leak`() {
        // ADR-0001 rule 2's named exception: the engine escape hatch.
        val surface = "\tpublic final fun getExoPlayer ()Landroidx/media3/exoplayer/ExoPlayer;"

        assertEquals(
            emptyList<String>(),
            findUnstableMedia3TypesInApiSurface(
                surface,
                allowed = ADR_0001_UNSTABLE_EXCEPTIONS,
                contractMembers = NO_CONTRACT_MEMBERS,
                isUnstable = { true },
            ),
        )
    }

    @Test
    fun `the escape hatch is the only exception this project grants by hand`() {
        // ADR-0001 says the exception is exactly one member. A second entry is a decision that
        // belongs in a superseding ADR, so it should fail here first.
        assertEquals(setOf("androidx/media3/exoplayer/ExoPlayer"), ADR_0001_UNSTABLE_EXCEPTIONS)
    }

    @Test
    fun `a member the Player contract itself declares is not SuperPlayer's leak`() {
        // `Player.getSurfaceSize()` returns an @UnstableApi Size even though Player is stable, so
        // every implementation of Player declares it. Rule 2 is about instability SuperPlayer chose.
        val surface = "\tpublic fun getSurfaceSize ()Landroidx/media3/common/util/Size;"

        assertEquals(
            emptyList<String>(),
            findUnstableMedia3TypesInApiSurface(
                surface,
                allowed = emptySet(),
                contractMembers = setOf(SURFACE_SIZE),
                isUnstable = { true },
            ),
        )
    }

    @Test
    fun `the same unstable type on a member SuperPlayer invented is still a leak`() {
        // The permission belongs to the member Media3 declares, not to the type at large. A facade
        // that chose to publish a Size of its own is exactly what rule 2 is written about.
        val surface = "\tpublic final fun getLastKnownSurfaceSize ()Landroidx/media3/common/util/Size;"

        assertTrue(
            findUnstableMedia3TypesInApiSurface(
                surface,
                allowed = emptySet(),
                contractMembers = setOf(SURFACE_SIZE),
                isUnstable = { true },
            ).single().startsWith("androidx/media3/common/util/Size:")
        )
    }

    @Test
    fun `a non-Media3 type is never a leak`() {
        val surface = "\tpublic final fun getContext ()Landroid/content/Context;"

        assertEquals(
            emptyList<String>(),
            findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true }),
        )
    }

    @Test
    fun `a nested type is reported under its own name`() {
        val surface = "\tpublic fun addListener (Landroidx/media3/common/Player\$Listener;)V"

        assertTrue(
            findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true })
                .single()
                .startsWith("androidx/media3/common/Player\$Listener:")
        )
    }

    @Test
    fun `one type leaking through several signatures is reported once per signature`() {
        val surface = listOf(
            "\tpublic final fun a ()Landroidx/media3/exoplayer/LoadControl;",
            "\tpublic final fun b ()Landroidx/media3/exoplayer/LoadControl;",
        ).joinToString("\n")

        // Two entries, because a contributor fixing this has two call sites to change, and the
        // signature is what tells them where.
        assertEquals(2, findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true }).size)
    }

    @Test
    fun `leaks are sorted so the failure message is stable`() {
        val surface = listOf(
            "\tpublic final fun z ()Landroidx/media3/exoplayer/TrackSelector;",
            "\tpublic final fun a ()Landroidx/media3/exoplayer/LoadControl;",
        ).joinToString("\n")

        val leaks = findUnstableMedia3TypesInApiSurface(surface, emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true })

        assertEquals(leaks.sorted(), leaks)
    }

    @Test
    fun `an empty surface has no leaks`() {
        assertEquals(
            emptyList<String>(),
            findUnstableMedia3TypesInApiSurface("", emptySet(), NO_CONTRACT_MEMBERS, isUnstable = { true }),
        )
    }

    @Test
    fun `a Player contract that cannot be read is null rather than empty`() {
        // Null and "no members" must not be the same value: an empty contract would silently permit
        // nothing and pass, which for a rule-2 check is the one unacceptable outcome. The task
        // turns this null into a failure.
        assertNull(playerContractMembers(emptyList()))
    }

    @Test
    fun `a surface naming no Media3 type needs no classpath`() {
        // Every placeholder module, today.
        assertFalse(namesAnyMedia3Type(""))
        assertFalse(namesAnyMedia3Type("\tpublic final fun getContext ()Landroid/content/Context;"))
        assertTrue(namesAnyMedia3Type("\tpublic fun getCurrentTracks ()Landroidx/media3/common/Tracks;"))
    }

    private companion object {
        /** `Player.getSurfaceSize()` as the dump and the class file both spell it. */
        const val SURFACE_SIZE = "getSurfaceSize ()Landroidx/media3/common/util/Size;"

        val NO_CONTRACT_MEMBERS = emptySet<String>()
    }
}
