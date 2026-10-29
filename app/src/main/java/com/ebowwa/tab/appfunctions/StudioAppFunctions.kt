package com.ebowwa.tab.appfunctions

import android.content.Intent
import androidx.appfunctions.AppFunctionDeclaration
import com.ebowwa.tab.core.agent.HtmlValidator
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.ebowwa.tab.MainActivity
import com.ebowwa.tab.core.agent.StudioRequests

/**
 * A request handed from the system agent to the UI.
 *
 * On-device generation is foreground-only (ML Kit GenAI fails with ErrorCode 30 in a
 * service), so an AppFunction cannot generate. It queues the prompt here and the Activity
 * consumes it as soon as it is on screen.
 */

/**
 * How the *system* Gemini agent drives Studio.
 *
 * Abstract on purpose: the AppFunctions KSP processor generates the concrete service that
 * routes function ids to the methods below. Never implement onExecuteFunction here.
 */
@AppFunctionServiceEntryPoint(
    serviceName = "StudioAppFunctionService",
    appFunctionXmlFileName = "studio_app_functions",
)
abstract class StudioAppFunctions : AppFunctionService() {

    /**
     * Builds a small playable game from a description. Studio opens and generates it on the
     * device with Gemini Nano, then renders it so it can be played immediately.
     *
     * @param prompt What the game should be, for example "catch falling stars with a basket".
     * @return Whether Studio was opened, or what went wrong.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun build_game(prompt: String): String {
        if (prompt.isBlank()) {
            return "Refused: I need a description of the game to build."
        }

        StudioRequests.queue(prompt)

        // Background activity starts can be blocked, so this is best-effort. If it does not
        // come up, the queued prompt is still consumed the next time Studio is opened.
        val opened = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("prompt", prompt)
                },
            )
        }.isSuccess

        return if (opened) {
            "Opening Studio to build \"$prompt\". It generates on-device with Gemini Nano."
        } else {
            "Queued \"$prompt\". Open Studio and it will start building straight away."
        }
    }

    /**
     * Installs a complete game that the assistant wrote. Studio validates it and renders it;
     * it does not generate anything itself.
     *
     * Use this when you can write the HTML yourself - it is far better than asking the phone's
     * small on-device model to write it. The document must be self-contained: inline CSS and
     * JavaScript, no external resources, one canvas.
     *
     * @param html The complete HTML document for the game.
     * @return Whether it was installed, or exactly why it was refused.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun install_game(html: String): String {
        if (html.isBlank()) return "Refused: the html parameter was empty."

        val verdict = HtmlValidator.validate(html)
        val page = verdict.html
            ?: return "Refused: ${verdict.reason}"

        StudioRequests.queueHtml(page)
        val opened = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }.isSuccess

        return if (opened) {
            "Installed (${page.length} chars) and opened Studio."
        } else {
            "Installed (${page.length} chars). Open Studio to play it."
        }
    }

    /**
     * Reports what Studio can do and whether the on-device model is usable right now.
     *
     * @return A short description of Studio's capability and its model status.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun studio_status(): String {
        val queued = StudioRequests.peek()
        return buildString {
            append("Studio runs self-contained HTML games. Two ways in: ")
            append("install_game(html) if you write the document yourself - preferred, since you ")
            append("are a much stronger model than the phone's on-device one; or build_game(prompt) ")
            append("to have Gemini Nano write it on the device with no network. ")
            if (queued != null) append("A build is already queued: \"$queued\".")
        }
    }
}
