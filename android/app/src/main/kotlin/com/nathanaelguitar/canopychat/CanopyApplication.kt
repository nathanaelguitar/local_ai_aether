package com.nathanaelguitar.canopychat

import android.app.Application
import com.nathanaelguitar.canopychat.core.CanopyNotifications
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Application-wide startup. PdfBox-Android needs its font resources loaded before any
 * document is parsed, and the reply notification channel needs to exist before the first
 * notification is posted — both are done here rather than on first use.
 */
class CanopyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        CanopyNotifications.ensureChannel(this)
    }
}
