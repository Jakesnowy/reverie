package io.github.xororz.localdream.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.ui.components.ImportParametersDialog
import io.github.xororz.localdream.ui.components.ShareParamsFlow
import io.github.xororz.localdream.utils.ParamShareField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Parameter share/import dialog region of [ModelRunScreen]: the outbound
 * share flow and the inbound import confirmation. The observed share
 * preferences are collected *inside* this scope so preference flips and
 * dialog flag changes never recompose the screen orchestrator; the clipboard
 * *detection* effect stays with the screen. Parameter application persistence
 * is passed in as [onSaveAllFields].
 */
@Composable
internal fun RunShareImportDialogs(
    shareState: RunShareImportState,
    runState: RunGenerationState,
    promptField: PromptFieldController,
    negativePromptField: PromptFieldController,
    generationPreferences: GenerationPreferences,
    scope: CoroutineScope,
    onSaveAllFields: () -> Unit,
) {
    val context = LocalContext.current
    val shareUseBase64 by remember { generationPreferences.observeShareUseBase64() }
        .collectAsState(initial = true)
    val clearClipboardInitial by remember {
        generationPreferences.observeShareClearClipboardOnImport()
    }.collectAsState(initial = true)
    val msgImportApplied = stringResource(R.string.import_applied)

    // Share parameters dialog
    shareState.shareSourceParams?.let { source ->
        ShareParamsFlow(
            source = source,
            modelId = shareState.shareSourceModelId,
            useBase64Initial = shareUseBase64,
            onUseBase64Changed = { value ->
                scope.launch { generationPreferences.setShareUseBase64(value) }
            },
            onCopied = { shareState.clipboardImportChecked = true },
            onDismiss = {
                shareState.shareSourceParams = null
                shareState.shareSourceModelId = null
            },
        )
    }

    // Import shared parameters dialog
    shareState.pendingImport?.let { imported ->
        val clearClipboardAction = {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
            runCatching {
                // Build.VERSION_CODES.P is API 28, minSdk = 28, so the legacy
                // setPrimaryClip(empty) fallback is unreachable.
                clipboard?.clearPrimaryClip()
            }
        }
        ImportParametersDialog(
            imported = imported,
            clearClipboardInitial = clearClipboardInitial,
            onClearClipboardChanged = { value ->
                scope.launch {
                    generationPreferences.setShareClearClipboardOnImport(value)
                }
            },
            onApply = { selectedFields, clearClipboard ->
                if (ParamShareField.PROMPT in selectedFields) {
                    imported.prompt?.let { promptField.replaceText(it) }
                }
                if (ParamShareField.NEGATIVE_PROMPT in selectedFields) {
                    imported.negativePrompt?.let { negativePromptField.replaceText(it) }
                }
                if (ParamShareField.STEPS in selectedFields) {
                    imported.steps?.let {
                        runState.steps = it.toFloat().coerceIn(GenerationDefaults.STEPS_RANGE)
                    }
                }
                if (ParamShareField.CFG in selectedFields) {
                    imported.cfg?.let { runState.cfg = it.coerceIn(GenerationDefaults.CFG_RANGE) }
                }
                if (ParamShareField.SEED in selectedFields) {
                    runState.seed = imported.seed?.toString() ?: ""
                }
                if (ParamShareField.SCHEDULER in selectedFields) {
                    imported.scheduler?.let { runState.scheduler = it }
                }
                if (ParamShareField.DENOISE_STRENGTH in selectedFields) {
                    imported.denoiseStrength?.let {
                        runState.denoiseStrength = it.coerceIn(GenerationDefaults.DENOISE_RANGE)
                    }
                }
                onSaveAllFields()
                if (clearClipboard) {
                    clearClipboardAction()
                }
                shareState.pendingImport = null
                Toast.makeText(
                    context,
                    msgImportApplied,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onDismiss = { clearClipboard ->
                if (clearClipboard) {
                    clearClipboardAction()
                }
                shareState.pendingImport = null
            },
        )
    }
}
