package com.ebowwa.tab.core.agent

/**
 * Deterministic checks on a generated game. These cannot be talked out of a verdict by the
 * model, so they run before the model's own critique.
 */
object GameAudit {

    fun issues(html: String): List<String> {
        val s = html.lowercase()
        val out = mutableListOf<String>()

        if (!s.contains("requestanimationframe")) {
            out.add("There is no animation loop: call requestAnimationFrame(draw) at the end of draw.")
        }
        if (!s.contains("pointerdown") && !s.contains("touchstart") && !s.contains("onclick")) {
            out.add("There are no touch or click controls, so it cannot be played on a phone.")
        }
        if (!s.contains("filltext")) {
            out.add("The score is never drawn: use fillText to show it on the canvas.")
        }
        if (!s.contains("math.random") && !s.contains("setinterval")) {
            out.add("Nothing new ever appears: spawn objects on a timer.")
        }
        if (html.length < 500) {
            out.add("The document is too small to contain a working game.")
        }
        return out
    }
}
