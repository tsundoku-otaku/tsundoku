package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Preference widget for editing a multi-line LLM prompt template.
 * Prefilled with [defaultValue] when [value] is blank, and resettable back to it.
 */
@Composable
fun PromptPreferenceWidget(
    title: String,
    subtitle: String?,
    value: String,
    defaultValue: String,
    onConfirm: suspend (String) -> Boolean,
    onReset: () -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = null,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val scope = rememberCoroutineScope()
        val initialValue = value.ifBlank { defaultValue }
        val onDismissRequest = { isDialogShown = false }
        var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            mutableStateOf(TextFieldValue(initialValue))
        }
        var isResetConfirmShown by rememberSaveable { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = title) },
            text = {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    trailingIcon = {
                        if (textFieldValue.text.isBlank()) {
                            Icon(imageVector = Icons.Filled.Error, contentDescription = null)
                        }
                    },
                    isError = textFieldValue.text.isBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                )
            },
            properties = DialogProperties(usePlatformDefaultWidth = true),
            confirmButton = {
                TextButton(
                    enabled = textFieldValue.text != initialValue && textFieldValue.text.isNotBlank(),
                    onClick = {
                        scope.launch {
                            if (onConfirm(textFieldValue.text)) {
                                onDismissRequest()
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                if (value.isNotBlank()) {
                    TextButton(onClick = { isResetConfirmShown = true }) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )

        if (isResetConfirmShown) {
            AlertDialog(
                onDismissRequest = { isResetConfirmShown = false },
                title = { Text(text = stringResource(MR.strings.action_reset)) },
                text = { Text(text = stringResource(MR.strings.pref_translation_reset_prompt_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onReset()
                            isResetConfirmShown = false
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { isResetConfirmShown = false }) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}
