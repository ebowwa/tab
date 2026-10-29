package com.ebowwa.tab.appfunctions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.ebowwa.tab.MainActivity
import com.ebowwa.tab.core.agent.HtmlValidator
import com.ebowwa.tab.core.agent.StudioRequests

/**
 * Non-privileged way to hand Studio a finished game.
 *
 * The AppFunctions route requires `android.permission.EXECUTE_APP_FUNCTIONS`, which is
 * signature|privileged: only a system app such as Gemini can hold it. adb shell and ordinary
 * apps get "Missing Privileged Access" instead. This receiver is the demo path - it accepts
 * the same document, runs the same [HtmlValidator] trust boundary, and opens the same UI.
 *
 *   adb shell am broadcast -a com.ebowwa.tab.INSTALL_GAME \
 *     -n com.ebowwa.tab/.appfunctions.InstallReceiver --es html_b64 <base64>
 *
 * base64 so the payload survives shell quoting.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val b64 = intent.getStringExtra(EXTRA_HTML_B64)
        val raw = intent.getStringExtra(EXTRA_HTML)
        val html = when {
            b64 != null -> runCatching {
                String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrNull()
            else -> raw
        }

        if (html.isNullOrBlank()) {
            Log.w(TAG, "install ignored: no html extra")
            return
        }

        val verdict = HtmlValidator.validate(html)
        val page = verdict.html
        if (page == null) {
            Log.w(TAG, "install refused: ${verdict.reason}")
            return
        }

        StudioRequests.queueHtml(page)
        Log.i(TAG, "installed ${page.length} chars")

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    companion object {
        private const val TAG = "StudioInstall"
        const val ACTION = "com.ebowwa.tab.INSTALL_GAME"
        const val EXTRA_HTML_B64 = "html_b64"
        const val EXTRA_HTML = "html"
    }
}
