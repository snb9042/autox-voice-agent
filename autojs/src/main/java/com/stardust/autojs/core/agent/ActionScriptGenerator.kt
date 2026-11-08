package com.stardust.autojs.core.agent

object ActionScriptGenerator {

    private const val FIND_TIMEOUT_MS = 10_000
    private const val POLL_INTERVAL_MS = 300

    /**
     * Pixel slack applied to a recorded rect before matching, so that a small
     * layout shift (status bar, keyboard, scroll offset) doesn't turn an exact
     * rect filter into a guaranteed timeout.
     */
    private const val BOUNDS_TOLERANCE_PX = 24

    private val PRELUDE = """
        |auto.waitFor();
        |
        |function __findTextNear(targetText, cx, cy, timeoutMs) {
        |    var start = Date.now();
        |    while (Date.now() - start < timeoutMs) {
        |        var matches = text(targetText).find();
        |        if (!matches.empty()) {
        |            var best = matches.get(0);
        |            var bestDist = Infinity;
        |            for (var i = 0; i < matches.size(); i++) {
        |                var b = matches.get(i).bounds();
        |                var dx = (b.left + b.right) / 2 - cx;
        |                var dy = (b.top + b.bottom) / 2 - cy;
        |                var dist = dx * dx + dy * dy;
        |                if (dist < bestDist) { bestDist = dist; best = matches.get(i); }
        |            }
        |            return best;
        |        }
        |        sleep($POLL_INTERVAL_MS);
        |    }
        |    return null;
        |}
        |
        |function __findDescNear(targetText, cx, cy, timeoutMs) {
        |    var start = Date.now();
        |    while (Date.now() - start < timeoutMs) {
        |        var matches = desc(targetText).find();
        |        if (!matches.empty()) {
        |            var best = matches.get(0);
        |            var bestDist = Infinity;
        |            for (var i = 0; i < matches.size(); i++) {
        |                var b = matches.get(i).bounds();
        |                var dx = (b.left + b.right) / 2 - cx;
        |                var dy = (b.top + b.bottom) / 2 - cy;
        |                var dist = dx * dx + dy * dy;
        |                if (dist < bestDist) { bestDist = dist; best = matches.get(i); }
        |            }
        |            return best;
        |        }
        |        sleep($POLL_INTERVAL_MS);
        |    }
        |    return null;
        |}
        |
        |function __findBounds(l, t, r, b, timeoutMs) {
        |    var m = $BOUNDS_TOLERANCE_PX;
        |    var cx = (l + r) / 2;
        |    var cy = (t + b) / 2;
        |    var start = Date.now();
        |    while (Date.now() - start < timeoutMs) {
        |        // Tolerant pass: any editable node sitting within a slightly
        |        // widened version of the recorded rect, nearest-center first.
        |        var matches = editable(true).boundsInside(l - m, t - m, r + m, b + m).find();
        |        if (!matches.empty()) {
        |            var best = matches.get(0);
        |            var bestDist = Infinity;
        |            for (var i = 0; i < matches.size(); i++) {
        |                var nb = matches.get(i).bounds();
        |                var dx = (nb.left + nb.right) / 2 - cx;
        |                var dy = (nb.top + nb.bottom) / 2 - cy;
        |                var dist = dx * dx + dy * dy;
        |                if (dist < bestDist) { bestDist = dist; best = matches.get(i); }
        |            }
        |            return best;
        |        }
        |        // Exact pass, for a target that is not reported as editable.
        |        var exact = bounds(l, t, r, b).find();
        |        if (!exact.empty()) return exact.get(0);
        |        sleep($POLL_INTERVAL_MS);
        |    }
        |    return null;
        |}
    """.trimMargin()

    private const val SCREEN_CAPTURE_GUARD =
        "if (!images.requestScreenCapture()) throw Error(\"Screen capture permission is required for image-matching steps in this script\");"

    fun generate(actions: List<SemanticAction>): String {
        val out = StringBuilder(PRELUDE).append("\n\n")
        if (actions.any { it.type == ActionType.CLICK_IMAGE }) {
            // Only prompt when the script actually needs the permission.
            out.append(SCREEN_CAPTURE_GUARD).append("\n\n")
        }
        actions.forEachIndexed { index, action ->
            out.append(generateStep(action, index)).append("\n\n")
        }
        return out.toString()
    }

    private fun generateStep(action: SemanticAction, index: Int): String = when (action.type) {
        ActionType.CLICK_TEXT -> clickOrLongClickByText(action, index, "click")
        ActionType.LONG_CLICK -> clickOrLongClickByText(action, index, "longClick")
        ActionType.FILL_TEXT -> fillTextStep(action, index)
        ActionType.CLICK_IMAGE -> clickImageStep(action, index)
        ActionType.CLICK_BOUNDS -> clickBoundsStep(action, index)
        ActionType.SCROLL -> scrollStep(action, index)
    }

    /**
     * Escapes a value for embedding inside a double-quoted JS string literal.
     * Backslashes must go first so the escapes added afterwards aren't doubled.
     * Line terminators matter too: a raw newline (or U+2028/U+2029, which are
     * line terminators in JS) inside a string literal is a parse error.
     */
    private fun esc(text: String) = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    /**
     * Escapes a value for embedding in a `//` comment. [esc] already removes
     * every raw line terminator; this collapses anything that somehow survives,
     * because in a comment a line terminator is a statement boundary rather
     * than a string-literal error.
     */
    private fun comment(text: String) = esc(text).replace(LINE_TERMINATORS, " ")

    private val LINE_TERMINATORS = Regex("[\\r\\n\\u2028\\u2029]")

    /** `text(...)` only matches getText(); a desc-sourced label needs `desc(...)`. */
    private fun finderFor(matchBy: MatchBy?): String =
        // null is treated as TEXT purely defensively, for any construction that
        // predates matchBy - new code always sets it explicitly.
        if (matchBy == MatchBy.DESC) "__findDescNear" else "__findTextNear"

    private fun clickOrLongClickByText(action: SemanticAction, index: Int, method: String): String {
        val text = requireNotNull(action.targetText) { "Step $index (${action.type}) has no targetText" }
        val finder = finderFor(action.matchBy)
        return """
            |// Step $index: $method "${comment(text)}"
            |var step$index = $finder("${esc(text)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
            |if (!step$index) throw Error("Step $index: target \"${esc(text)}\" not found");
            |if (!step$index.$method()) throw Error("Step $index: node found but $method() failed");
        """.trimMargin()
    }

    private fun fillTextStep(action: SemanticAction, index: Int): String {
        val value = esc(action.fillValue.orEmpty())
        val (l, t, r, b) = action.bounds
        return if (action.targetText != null) {
            val finder = finderFor(action.matchBy)
            """
                |// Step $index: fill "${comment(action.targetText)}"
                |var step$index = $finder("${esc(action.targetText)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
                |if (!step$index) throw Error("Step $index: target \"${esc(action.targetText)}\" not found");
                |if (!step$index.setText("$value")) throw Error("Step $index: node found but setText() failed");
            """.trimMargin()
        } else {
            """
                |// Step $index: fill field at bounds
                |var step$index = __findBounds($l, $t, $r, $b, $FIND_TIMEOUT_MS);
                |if (!step$index) throw Error("Step $index: field at bounds ($l, $t, $r, $b) not found");
                |if (!step$index.setText("$value")) throw Error("Step $index: node found but setText() failed");
            """.trimMargin()
        }
    }

    private fun clickImageStep(action: SemanticAction, index: Int): String {
        val templatePath = requireNotNull(action.imageTemplatePath) { "Step $index (CLICK_IMAGE) has no imageTemplatePath" }
        val tap = if (action.isLongClick) "longClick" else "click"
        return """
            |// Step $index: $tap image target
            |var step${index}Template = images.read("${esc(templatePath)}");
            |if (!step${index}Template) throw Error("Step $index: template image \"${esc(templatePath)}\" could not be read");
            |var step${index}Point = null;
            |var step${index}Start = Date.now();
            |while (!step${index}Point && Date.now() - step${index}Start < $FIND_TIMEOUT_MS) {
            |    var step${index}Screen = images.captureScreen();
            |    step${index}Point = images.findImage(step${index}Screen, step${index}Template);
            |    if (!step${index}Point) sleep($POLL_INTERVAL_MS);
            |}
            |if (!step${index}Point) throw Error("Step $index: image target not found on screen");
            |if (!$tap(step${index}Point.x, step${index}Point.y)) throw Error("Step $index: $tap at the matched image point failed");
        """.trimMargin()
    }

    private fun clickBoundsStep(action: SemanticAction, index: Int): String {
        val tap = if (action.isLongClick) "longClick" else "click"
        return """
            |// Step $index: fallback fixed-coordinate $tap - consider re-recording with screen-capture allowed
            |if (!$tap(${action.bounds.centerX()}, ${action.bounds.centerY()})) throw Error("Step $index: $tap at (${action.bounds.centerX()}, ${action.bounds.centerY()}) failed");
        """.trimMargin()
    }

    private fun scrollStep(action: SemanticAction, index: Int): String {
        val direction = requireNotNull(action.scrollDirection) { "Step $index (SCROLL) has no scrollDirection" }
        val bounds = action.bounds
        // The recorder's directions are content-relative: DOWN means the
        // content's scroll position increased, i.e. further-down content is now
        // visible. Reproducing that means dragging the content up, so the
        // finger travels from the bottom of the control toward the top.
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.DOWN -> listOf(bounds.centerX(), bounds.bottom, bounds.centerX(), bounds.top)
            ScrollDirection.UP -> listOf(bounds.centerX(), bounds.top, bounds.centerX(), bounds.bottom)
            ScrollDirection.RIGHT -> listOf(bounds.right, bounds.centerY(), bounds.left, bounds.centerY())
            ScrollDirection.LEFT -> listOf(bounds.left, bounds.centerY(), bounds.right, bounds.centerY())
        }
        return """
            |// Step $index: scroll $direction
            |if (!swipe($startX, $startY, $endX, $endY, 300)) throw Error("Step $index: scroll $direction failed");
        """.trimMargin()
    }
}
