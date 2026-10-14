/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.offline

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.cache.CachePolicy
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * ADR-0013 rule 15: a player and a cache that no download store was opened over register and allocate
 * nothing for downloads. Nothing about a player's builder changed, so what could be paid is what a store
 * brings — the requirements watcher Media3's download manager registers with the platform, and the
 * download index table it keeps in the cache's database — and each is counted with no store and again
 * with one, so the counter is shown to see what it counts.
 *
 * Rule 3's half of paying nothing is the manifest: the service a download outlives its screen in, and the
 * foreground-service and notification permissions it needs, are the app's to declare, so this module's own
 * manifest declares none of them and the manifest merged into a test's declares no service of the module's.
 */
@RunWith(AndroidJUnit4::class)
class DownloadsPayNothingTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aPlayerAndACacheWithNoStoreRegisterNothingAndCreateNoDownloadIndex() {
        val content = TestContent.hls()
        val alone = folder.newFolder()
        val cache = CachePolicy.contentKeyed(alone, BUDGET_BYTES)
        val player = harness.buildPlayer(content = content, cache = cache)
        player.setMediaRequest(MediaRequest.Builder("film/alone").addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED }
        val withoutStore = registrations()

        val attached = folder.newFolder()
        val storeCache = CachePolicy.contentKeyed(attached, BUDGET_BYTES)
        val downloads = Downloads.Builder(context, storeCache).build()
        // Reads the index, which is what brings its table into being.
        assertThat(downloads.downloads()).isEmpty()
        val withStore = registrations()
        downloads.release()
        val afterRelease = registrations()

        assertWithMessage("a store registers what the counter counts").that(withStore).isNotEqualTo(withoutStore)
        assertWithMessage("and lets go of it on release").that(afterRelease).isEqualTo(withoutStore)

        harness.release(player)
        cache.release()
        storeCache.release()
        assertWithMessage("a cache nothing downloaded into").that(downloadTables(alone)).isEmpty()
        assertWithMessage("a cache a store was opened over").that(downloadTables(attached)).isNotEmpty()
    }

    @Test
    fun theModulesManifestDeclaresNothingRuleThreeSaysIsTheApps() {
        // The module's own manifest, as published: `FOREGROUND_SERVICE` included, which WorkManager merges on its own.
        val own = File("src/main/AndroidManifest.xml").readText()
        assertThat(own).contains("android.permission.ACCESS_NETWORK_STATE")
        listOf("<service", "android.permission.FOREGROUND_SERVICE", "android.permission.POST_NOTIFICATIONS").forEach { appOwned ->
            assertWithMessage("this module's manifest").that(own).doesNotContain(appOwned)
        }

        // The merged manifest, which carries every library's entries: WorkManager's services are there, which is what
        // shows the reading sees what a library merges.
        val merged = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS,
        )
        val services = merged.services.orEmpty().map { it.name }
        assertThat(services).contains("androidx.work.impl.background.systemjob.SystemJobService")
        assertWithMessage("services merged from this module").that(services.filter { it.startsWith("com.superplayer.offline") }).isEmpty()
        assertThat(merged.requestedPermissions.orEmpty().toList())
            .containsNoneOf("android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS")
    }

    /** Broadcast receivers and network callbacks registered with the platform, as a pair. */
    private fun registrations(): Pair<Int, Int> = Pair(
        shadowOf(context as Application).registeredReceivers.size,
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.size,
    )

    /** The tables of Media3's download index in the cache database inside [directory]. */
    private fun downloadTables(directory: File): List<String> {
        val index = checkNotNull(directory.listFiles { file -> file.name.endsWith(".db") }?.singleOrNull()) { "no index in $directory" }
        return SQLiteDatabase.openDatabase(index.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'ExoPlayerDownloads%'", null).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        }
    }

    private companion object {
        const val BUDGET_BYTES = 64L * 1024 * 1024
    }
}
