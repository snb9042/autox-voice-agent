package org.autojs.autojs.ui.agent

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.afollestad.materialdialogs.DialogAction
import com.afollestad.materialdialogs.MaterialDialog
import com.makeramen.roundedimageview.RoundedImageView
import com.stardust.app.DialogUtils
import com.stardust.autojs.core.agent.ActionOptimizer
import com.stardust.autojs.core.agent.ActionScriptGenerator
import com.stardust.autojs.core.agent.ActionType
import com.stardust.autojs.core.agent.SemanticAction
import com.stardust.autojs.core.agent.SemanticRecorder
import com.stardust.autojs.util.FloatingPermission
import com.stardust.enhancedfloaty.FloatyService
import org.autojs.autojs.Pref
import org.autojs.autojs.autojs.AutoJs
import org.autojs.autojs.model.explorer.ExplorerDirPage
import org.autojs.autojs.model.explorer.Explorers
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.theme.dialog.ThemeColorMaterialDialogBuilder
import org.autojs.autojs.tool.AccessibilityServiceTool
import org.autojs.autojs.ui.common.ScriptOperations
import org.autojs.autojs.ui.explorer.ExplorerViewKt
import org.autojs.autojs.ui.floating.CircularActionMenu
import org.autojs.autojs.ui.floating.CircularMenuFloaty
import org.autojs.autojs.ui.floating.CircularMenuWindow
import org.autojs.autoxjs.R
import java.io.File

class AgentBubble(context: Context) {

    private val mContext: Context = ContextThemeWrapper(context, R.style.AppTheme)
    private var mWindow: CircularMenuWindow? = null
    private var mIcon: RoundedImageView? = null
    private val mRecorder: SemanticRecorder = AutoJs.getInstance().semanticRecorder

    init {
        initFloaty()
    }

    private fun initFloaty() {
        // Known, accepted limitation: CircularMenuWindow persists its position
        // under keys derived from its own class name, so this bubble and the
        // legacy CircularMenu bubble share one saved position and whichever
        // closes last wins. Fixing that would mean changing the shared
        // CircularMenuWindow, which this feature deliberately leaves untouched.
        mWindow = CircularMenuWindow(
            mContext,
            object : CircularMenuFloaty {
                override fun inflateActionView(service: FloatyService, window: CircularMenuWindow): View {
                    val view = View.inflate(service, R.layout.agent_bubble_action_view, null)
                    mIcon = view.findViewById(R.id.agent_icon)
                    // The recorder is a process-wide singleton, so reflect its
                    // real state instead of assuming idle.
                    updateIcon()
                    return view
                }

                override fun inflateMenuItems(service: FloatyService, window: CircularMenuWindow): CircularActionMenu {
                    val menu = View.inflate(
                        ContextThemeWrapper(service, R.style.AppTheme),
                        R.layout.agent_bubble_action_menu,
                        null
                    ) as CircularActionMenu
                    menu.findViewById<View>(R.id.agent_record)?.setOnClickListener { startRecording() }
                    menu.findViewById<View>(R.id.agent_stop)?.setOnClickListener { stopRecording() }
                    menu.findViewById<View>(R.id.agent_play)?.setOnClickListener { pickAndPlay() }
                    menu.findViewById<View>(R.id.agent_stop_all_scripts)
                        ?.setOnClickListener { stopAllScripts() }
                    return menu
                }
            }
        )
        // CircularMenuWindow.setListeners() already installs the
        // expand/collapse toggle on the action view, so there is nothing to
        // override here.
        mWindow?.setKeepToSideHiddenWidthRadio(0.25f)
        FloatyService.addWindow(mWindow)
    }

    private fun updateIcon() {
        mIcon?.setBackgroundResource(
            if (mRecorder.state == SemanticRecorder.State.RECORDING) R.drawable.circle_red
            else R.drawable.circle_white
        )
    }

    private fun startRecording() {
        mWindow?.collapse()
        // Starting again would clear the in-progress recording, silently
        // discarding everything captured so far.
        if (mRecorder.state == SemanticRecorder.State.RECORDING) return
        if (!AccessibilityServiceTool.isAccessibilityServiceEnabled(mContext)) {
            AccessibilityServiceTool.goToAccessibilitySetting()
            return
        }
        mRecorder.start()
        updateIcon()
    }

    private fun stopRecording() {
        mWindow?.collapse()
        // Nothing to stop - don't optimize, generate, or show a "0 step(s)
        // recorded" dialog for an accidental Stop tap.
        if (mRecorder.state != SemanticRecorder.State.RECORDING) return
        val actions = mRecorder.stop()
        updateIcon()
        val optimized = ActionOptimizer.optimize(actions)
        // Templates belonging to steps the optimizer dropped can never be
        // referenced again.
        discardTemplates(actions.filterNot { optimized.contains(it) })
        showSummaryDialog(optimized)
    }

    private fun stopAllScripts() {
        mWindow?.collapse()
        AutoJs.getInstance().scriptEngineService.stopAllAndToast()
    }

    private fun showSummaryDialog(actions: List<SemanticAction>) {
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_recorded)
                .content(mContext.getString(R.string.text_agent_recorded_steps, actions.size))
                .items(
                    mContext.getString(R.string.text_preview),
                    mContext.getString(R.string.text_new_file)
                )
                .itemsCallback { _, _, position, _ ->
                    when (position) {
                        0 -> showPreviewDialog(ActionScriptGenerator.generate(actions)) {
                            showSummaryDialog(actions)
                        }
                        1 -> promptSaveScript(actions)
                    }
                }
                .negativeText(R.string.text_cancel)
                .onNegative { _, _ -> discardTemplates(actions) }
                .cancelListener { discardTemplates(actions) }
                .canceledOnTouchOutside(false)
                .build()
        )
    }

    private fun showPreviewDialog(script: String, onDismiss: () -> Unit) {
        val textView = TextView(mContext).apply {
            text = script
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        val scrollView = ScrollView(mContext).apply { addView(textView) }
        DialogUtils.showDialog(
            ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_preview)
                .customView(scrollView, false)
                .positiveText(R.string.ok)
                .dismissListener { onDismiss() }
                .build()
        )
    }

    /**
     * Asks for a script name, then writes the script - and the image templates
     * it references - into the scripts directory. Driving the name prompt here
     * (rather than through ScriptOperations.newScriptFileForScript) is what
     * makes it possible to tell "saved" from "discarded" and clean up the
     * staged templates in the latter case.
     */
    private fun promptSaveScript(actions: List<SemanticAction>) {
        var saved = false
        var saveAttemptFailed = false
        val dialog = ThemeColorMaterialDialogBuilder(mContext)
            .title(R.string.text_name)
            .inputType(InputType.TYPE_CLASS_TEXT)
            // Without this the input callback fires only once, so the
            // collision check below would never see what the user typed.
            .alwaysCallInputCallback()
            .input(mContext.getString(R.string.text_please_input_name), "", false) { dialog, input ->
                validateScriptName(dialog, input)
            }
            .onPositive { dialog, _ ->
                val fileName = scriptFileNameFor(dialog.inputEditText?.text?.toString().orEmpty())
                when {
                    // Should be unreachable - the positive button is disabled
                    // for a blank name - but a blank submit must not be
                    // mistaken for a cancel and take the recording with it.
                    fileName == null -> saveAttemptFailed = true
                    saveScript(fileName, actions) -> {
                        saved = true
                        // The staged originals have been copied next to the
                        // script; their only reference now is the relocated one.
                        discardTemplates(actions)
                    }
                    // The write didn't happen. Keep the staged templates so the
                    // recording stays recoverable rather than destroying it on
                    // a failed save.
                    else -> saveAttemptFailed = true
                }
            }
            .negativeText(R.string.text_cancel)
            .dismissListener {
                // Declining to save means the staged templates are garbage.
                // A save that was attempted and failed is *not* a decline, so
                // it must not take the recording down with it.
                if (!saved && !saveAttemptFailed) discardTemplates(actions)
            }
            .build()
        // Run the validator once up front so the button starts out disabled for
        // the empty prefill, rather than relying on the dialog library to do it.
        validateScriptName(dialog, dialog.inputEditText?.text ?: "")
        DialogUtils.showDialog(dialog)
    }

    /**
     * Keeps the positive button (and so submitting at all) disabled unless the
     * typed name is non-blank and doesn't collide with an existing file -
     * mirroring ScriptOperations.validateInput, whose protection was lost when
     * this feature took over the name prompt.
     */
    private fun validateScriptName(dialog: MaterialDialog, input: CharSequence) {
        val editText = dialog.inputEditText ?: return
        val positiveButton = dialog.getActionButton(DialogAction.POSITIVE)
        val fileName = scriptFileNameFor(input.toString())
        if (fileName == null) {
            // Blank: just don't let it through, no error text needed.
            positiveButton?.isEnabled = false
            return
        }
        if (File(Pref.getScriptDirPath(), fileName).exists()) {
            editText.error = mContext.getString(R.string.text_file_exists)
            positiveButton?.isEnabled = false
        } else {
            editText.error = null
            positiveButton?.isEnabled = true
        }
    }

    /**
     * The script file name a raw input would be saved under, or null if the
     * input is blank. Shared by the input validator and the save itself so the
     * name that gets checked is always the name that gets written.
     *
     * Path separators would move the script out of the directory its templates
     * were copied into, breaking the relative template references.
     */
    private fun scriptFileNameFor(input: String): String? {
        val name = input.trim().replace(PATH_SEPARATORS, "_")
        if (name.isEmpty()) return null
        return if (name.endsWith(".js", ignoreCase = true)) name else "$name.js"
    }

    /** Returns true only if the script file was actually written to disk. */
    private fun saveScript(fileName: String, actions: List<SemanticAction>): Boolean {
        // ScriptOperations(context, null) targets Pref.getScriptDirPath(), so
        // that's the directory the script - and therefore its templates - lands
        // in. Templates are copied next to the script and referenced by bare
        // filename, which Auto.js resolves against the running script's own
        // directory; an absolute filesDir path would break on export/share and
        // on clearing app data.
        val scriptDir = File(Pref.getScriptDirPath())
        val target = File(scriptDir, fileName)
        // The dialog already refuses colliding names, but the file could have
        // appeared since the last keystroke - and createScriptFile would then
        // write nothing while leaving the pre-existing file in place, which the
        // post-write existence check below cannot tell from a success.
        if (target.exists()) return false
        val relocated = actions.map { action ->
            val staged = action.imageTemplatePath
            if (action.type != ActionType.CLICK_IMAGE || staged.isNullOrEmpty()) {
                action
            } else {
                val templateFileName = copyTemplateInto(scriptDir, staged)
                if (templateFileName != null) {
                    action.copy(imageTemplatePath = templateFileName)
                } else {
                    // Template lost; degrade to the fixed-coordinate fallback
                    // rather than emitting a step that can never load.
                    action.copy(type = ActionType.CLICK_BOUNDS, imageTemplatePath = null)
                }
            }
        }
        val script = ActionScriptGenerator.generate(relocated)
        // createScriptFile is void and only toasts on failure, so the only way
        // to know whether the write actually happened is to look.
        ScriptOperations(mContext, null).createScriptFile(target.path, script, false)
        return target.isFile
    }

    private fun copyTemplateInto(dir: File, stagedPath: String): String? = try {
        val source = File(stagedPath)
        if (!source.isFile) {
            null
        } else {
            dir.mkdirs()
            val target = File(dir, source.name)
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            target.name
        }
    } catch (e: Exception) {
        null
    }

    /** Deletes the staging-directory template files referenced by [actions]. */
    private fun discardTemplates(actions: List<SemanticAction>) {
        actions.asSequence()
            .filter { it.type == ActionType.CLICK_IMAGE }
            .mapNotNull { it.imageTemplatePath }
            .map { File(it) }
            // Only ever delete out of the recorder's own staging directory.
            .filter { it.parentFile?.name == TEMPLATE_STAGING_DIR_NAME }
            .forEach {
                try {
                    it.delete()
                } catch (e: Exception) {
                    // Best effort only.
                }
            }
    }

    private fun pickAndPlay() {
        mWindow?.collapse()
        val explorerView = ExplorerViewKt(mContext)
        explorerView.setExplorer(Explorers.workspace(), ExplorerDirPage.createRoot(Pref.getScriptDirPath()))
        explorerView.setDirectorySpanSize(2)
        val dialog = ThemeColorMaterialDialogBuilder(mContext)
            .title(R.string.text_run_script)
            .customView(explorerView, false)
            .positiveText(R.string.cancel)
            .build()
        explorerView.setOnItemOperatedListener { dialog.dismiss() }
        explorerView.setOnItemClickListener { _, item ->
            item?.let { Scripts.run(it.toScriptFile()) }
            dialog.dismiss()
        }
        DialogUtils.showDialog(dialog)
    }

    fun close() {
        // The recorder is a process-wide singleton: hiding the bubble mid
        // recording would otherwise leave it recording forever into a list
        // nobody drains.
        if (mRecorder.state == SemanticRecorder.State.RECORDING) {
            // Nothing useful can be done with a recording abandoned by hiding
            // the bubble, but its staged templates still need cleaning up.
            discardTemplates(mRecorder.stop())
        }
        try {
            mWindow?.close()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TEMPLATE_STAGING_DIR_NAME = "agent_templates"
        private val PATH_SEPARATORS = Regex("[/\\\\]")

        private var instance: AgentBubble? = null

        fun show(context: Context) {
            if (instance != null) return
            val appContext = context.applicationContext
            appContext.startService(Intent(appContext, FloatyService::class.java))
            // Safety net for callers that don't check the overlay permission
            // themselves, matching FloatyWindowManger.addWindow().
            if (!FloatingPermission.ensurePermissionGranted(appContext)) return
            instance = AgentBubble(appContext)
        }

        fun hide() {
            instance?.close()
            instance = null
        }

        fun isShowing(): Boolean = instance != null
    }
}
