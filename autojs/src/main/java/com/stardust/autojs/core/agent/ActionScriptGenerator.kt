package com.stardust.autojs.core.agent

object ActionScriptGenerator {

    private const val FIND_TIMEOUT_MS = 10_000
    private const val POLL_INTERVAL_MS = 300

    private val PRELUDE = """
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
        |function __findBounds(l, t, r, b, timeoutMs) {
        |    var start = Date.now();
        |    while (Date.now() - start < timeoutMs) {
        |        var matches = bounds(l, t, r, b).find();
        |        if (!matches.empty()) return matches.get(0);
        |        sleep($POLL_INTERVAL_MS);
        |    }
        |    return null;
        |}
    """.trimMargin()

    fun generate(actions: List<SemanticAction>): String {
        val body = StringBuilder()
        actions.forEachIndexed { index, action ->
            body.append(generateStep(action, index)).append("\n\n")
        }
        return PRELUDE + "\n\n" + body.toString()
    }

    private fun generateStep(action: SemanticAction, index: Int): String = when (action.type) {
        ActionType.CLICK_TEXT -> clickOrLongClickByText(action, index, "click")
        ActionType.LONG_CLICK -> clickOrLongClickByText(action, index, "longClick")
        ActionType.FILL_TEXT -> fillTextStep(action, index)
        ActionType.CLICK_IMAGE -> clickImageStep(action, index)
        ActionType.CLICK_BOUNDS -> clickBoundsStep(action, index)
        ActionType.SCROLL -> scrollStep(action, index)
    }

    private fun esc(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun clickOrLongClickByText(action: SemanticAction, index: Int, method: String): String {
        val text = requireNotNull(action.targetText) { "Step $index (${action.type}) has no targetText" }
        return """
            |// Step $index: $method "${esc(text)}"
            |var step$index = __findTextNear("${esc(text)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
            |if (!step$index) throw Error("Step $index: text target \"${esc(text)}\" not found");
            |step$index.$method();
        """.trimMargin()
    }

    private fun fillTextStep(action: SemanticAction, index: Int): String {
        val value = esc(action.fillValue.orEmpty())
        val (l, t, r, b) = action.bounds
        return if (action.targetText != null) {
            """
                |// Step $index: fill "${esc(action.targetText)}"
                |var step$index = __findTextNear("${esc(action.targetText)}", ${action.bounds.centerX()}, ${action.bounds.centerY()}, $FIND_TIMEOUT_MS);
                |if (!step$index) throw Error("Step $index: text target \"${esc(action.targetText)}\" not found");
                |step$index.setText("$value");
            """.trimMargin()
        } else {
            """
                |// Step $index: fill field at bounds
                |var step$index = __findBounds($l, $t, $r, $b, $FIND_TIMEOUT_MS);
                |if (!step$index) throw Error("Step $index: field at bounds ($l, $t, $r, $b) not found");
                |step$index.setText("$value");
            """.trimMargin()
        }
    }

    private fun clickImageStep(action: SemanticAction, index: Int): String {
        val templatePath = requireNotNull(action.imageTemplatePath) { "Step $index (CLICK_IMAGE) has no imageTemplatePath" }
        return """
            |// Step $index: click image target
            |var step${index}Template = images.read("${esc(templatePath)}");
            |var step${index}Point = null;
            |var step${index}Start = Date.now();
            |while (!step${index}Point && Date.now() - step${index}Start < $FIND_TIMEOUT_MS) {
            |    var step${index}Screen = images.captureScreen();
            |    step${index}Point = images.findImage(step${index}Screen, step${index}Template);
            |    if (!step${index}Point) sleep($POLL_INTERVAL_MS);
            |}
            |if (!step${index}Point) throw Error("Step $index: image target not found on screen");
            |click(step${index}Point.x, step${index}Point.y);
        """.trimMargin()
    }

    private fun clickBoundsStep(action: SemanticAction, index: Int): String {
        return """
            |// Step $index: fallback fixed-coordinate click - consider re-recording with screen-capture allowed
            |click(${action.bounds.centerX()}, ${action.bounds.centerY()});
        """.trimMargin()
    }

    private fun scrollStep(action: SemanticAction, index: Int): String {
        val direction = requireNotNull(action.scrollDirection) { "Step $index (SCROLL) has no scrollDirection" }
        val bounds = action.bounds
        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.UP -> listOf(bounds.centerX(), bounds.bottom, bounds.centerX(), bounds.top)
            ScrollDirection.DOWN -> listOf(bounds.centerX(), bounds.top, bounds.centerX(), bounds.bottom)
            ScrollDirection.LEFT -> listOf(bounds.right, bounds.centerY(), bounds.left, bounds.centerY())
            ScrollDirection.RIGHT -> listOf(bounds.left, bounds.centerY(), bounds.right, bounds.centerY())
        }
        return "// Step $index: scroll $direction\nswipe($startX, $startY, $endX, $endY, 300);"
    }
}
