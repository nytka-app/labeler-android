package io.github.nytka_labeler

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestTest {
    @Test
    fun `the app never asks to record audio`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val asked =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toList()

        assertFalse(asked.contains("android.permission.RECORD_AUDIO"))
        assertTrue(asked.contains("android.permission.INTERNET"))
    }
}
