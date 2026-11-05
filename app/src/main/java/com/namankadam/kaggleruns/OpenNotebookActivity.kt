package com.namankadam.kaggleruns

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle

/**
 * Invisible hop for widget row taps. List rows share one mutable PendingIntent template, and
 * Android 14+ only allows those to target an explicit component, so the URL is opened from here.
 */
class OpenNotebookActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri != null && uri.scheme == "https" && uri.host == "www.kaggle.com") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                // No browser installed; nothing sensible to do.
            }
        }
        finish()
    }
}
