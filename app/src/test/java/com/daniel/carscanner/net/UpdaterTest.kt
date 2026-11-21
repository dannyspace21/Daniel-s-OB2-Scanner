package com.daniel.carscanner.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** In-app updates: reading GitHub's release info safely. */
class UpdaterTest {

    private fun release(tag: String, url: String, name: String = "DanielsCarMechanicScanner-build-70.apk") = JSONObject("""
        {"tag_name":"$tag","name":"Build 70","body":"Notes",
         "assets":[{"name":"$name","browser_download_url":"$url","size":16000000}]}
    """)

    private val goodUrl = "https://github.com/dannyspace21/Daniel-s-OB2-Scanner/releases/download/build-70/DanielsCarMechanicScanner-build-70.apk"

    @Test fun findsNewerBuild() {
        val u = Updater.parse(release("build-70", goodUrl), currentBuild = 66)!!
        assertEquals(70, u.build)
        assertEquals(goodUrl, u.apkUrl)
    }

    @Test fun ignoresSameOrOlderBuild() {
        assertNull(Updater.parse(release("build-70", goodUrl), currentBuild = 70))
        assertNull(Updater.parse(release("build-60", goodUrl), currentBuild = 70))
    }

    @Test fun refusesApkFromAnotherSite() {
        assertNull(Updater.parse(release("build-70", "https://evil.example.com/app.apk"), 66))
        assertNull(Updater.parse(release("build-70", "http://github.com/x.apk"), 66))   // not https
    }

    @Test fun ignoresOtherTagsAndFiles() {
        assertNull(Updater.parse(release("v2.0", goodUrl), 66))
        assertNull(Updater.parse(release("build-70", goodUrl, name = "notes.txt"), 66))
        assertEquals(123, Updater.buildNumber("build-123"))
        assertNull(Updater.buildNumber("123"))
    }
}
