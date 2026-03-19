package tachiyomi.presentation.core.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.DisabledByDefault
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.toggle
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.secondaryItemAlpha

object SettingsItemsPaddings {
    val Horizontal = 24.dp
    val Vertical = 10.dp
}

@Composable
fun HeadingItem(labelRes: StringResource) {
    HeadingItem(stringResource(labelRes))
}

@Composable
fun HeadingItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.header,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
    )
}

@Composable
fun IconItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    BaseSettingsItem(
        label = label,
        widget = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onClick,
    )
}

@Composable
fun SortItem(label: String, sortDescending: Boolean?, onClick: () -> Unit) {
    val arrowIcon = when (sortDescending) {
        true -> Icons.Default.ArrowDownward
        false -> Icons.Default.ArrowUpward
        null -> null
    }

    BaseSortItem(
        label = label,
        icon = arrowIcon,
        onClick = onClick,
    )
}

@Composable
fun BaseSortItem(label: String, icon: ImageVector?, onClick: () -> Unit) {
    BaseSettingsItem(
        label = label,
        widget = {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        },
        onClick = onClick,
    )
}

@Composable
fun CheckboxItem(label: String, pref: Preference<Boolean>) {
    val checked by pref.collectAsState()
    CheckboxItem(
        label = label,
        checked = checked,
        onClick = { pref.toggle() },
    )
}

@Composable
fun CheckboxItem(label: String, checked: Boolean, onClick: () -> Unit) {
    BaseSettingsItem(
        label = label,
        widget = {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
            )
        },
        onClick = onClick,
    )
}

@Composable
fun RadioItem(label: String, selected: Boolean, onClick: () -> Unit) {
    BaseSettingsItem(
        label = label,
        widget = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        onClick = onClick,
    )
}

@Composable
fun StepperItem(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    valueRange: IntRange,
    step: Int = 1,
    defaultValue: Int? = null,
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        StepperInputDialog(
            value = value,
            valueRange = valueRange,
            defaultValue = defaultValue,
            onDismiss = { showDialog = false },
            onConfirm = {
                onChange(it)
                showDialog = false
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (value - step >= valueRange.first) onChange(value - step) },
                enabled = value > valueRange.first,
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "Decrease")
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(min = 32.dp)
                    .clickable { showDialog = true },
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = { if (value + step <= valueRange.last) onChange(value + step) },
                enabled = value < valueRange.last,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Increase")
            }
        }
    }
}

@Composable
fun StepperItem(
    label: String,
    pref: Preference<Int>,
    valueRange: IntRange,
    step: Int = 1,
) {
    val value by pref.collectAsState()
    StepperItem(
        label = label,
        value = value,
        onChange = { pref.set(it) },
        valueRange = valueRange,
        step = step,
        defaultValue = pref.defaultValue(),
    )
}

@Composable
fun StepperItem(
    label: String,
    pref: Preference<Float>,
    valueRange: IntRange,
    step: Int = 1,
    multiplier: Int,
) {
    val value by pref.collectAsState()
    StepperItem(
        label = label,
        value = (value * multiplier).toInt(),
        onChange = { pref.set(it / multiplier.toFloat()) },
        valueRange = valueRange,
        step = step,
        defaultValue = (pref.defaultValue() * multiplier).toInt(),
    )
}

@Composable
private fun StepperInputDialog(
    value: Int,
    valueRange: IntRange,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    defaultValue: Int? = null,
) {
    var input by remember { mutableStateOf(value.toString()) }
    val parsed = input.toIntOrNull()
    val isValid = parsed != null && parsed in valueRange

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter value (${valueRange.first}–${valueRange.last})") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.filter { c -> c.isDigit() } },
                singleLine = true,
                isError = !isValid,
                supportingText = if (!isValid) {
                    { Text("Must be between ${valueRange.first} and ${valueRange.last}") }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onConfirm(parsed!!) },
                enabled = isValid,
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            Row {
                if (defaultValue != null) {
                    TextButton(onClick = { onConfirm(defaultValue) }) {
                        Text("Default")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
fun SliderItem(
    value: Int,
    valueRange: IntProgression,
    label: String,
    onChange: (Int) -> Unit,
    steps: Int = with(valueRange) { (last - first) - 1 },
    valueString: String = value.toString(),
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    pillColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    BaseSliderItem(
        value = value,
        valueRange = valueRange,
        steps = steps,
        title = label,
        valueString = valueString,
        onChange = onChange,
        titleStyle = labelStyle,
        pillColor = pillColor,
        modifier = Modifier.padding(
            horizontal = SettingsItemsPaddings.Horizontal,
            vertical = SettingsItemsPaddings.Vertical,
        ),
    )
}

@Composable
fun BaseSliderItem(
    value: Int,
    valueRange: IntProgression,
    title: String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    steps: Int = with(valueRange) { (last - first) - 1 },
    valueString: String = value.toString(),
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    subtitleStyle: TextStyle = MaterialTheme.typography.bodySmall,
    pillColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = subtitleStyle,
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                }
            }
            Pill(
                text = valueString,
                style = MaterialTheme.typography.bodyMedium,
                color = pillColor,
            )
        }
        Slider(
            value = value,
            onValueChange = f@{
                if (it == value) return@f
                onChange(it)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
@PreviewLightDark
fun SliderItemPreview() {
    MaterialTheme(if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var value by remember { mutableIntStateOf(0) }
        Surface {
            BaseSliderItem(
                value = value,
                valueRange = 0..10,
                title = "Item per row",
                valueString = if (value == 0) "Auto" else value.toString(),
                onChange = { value = it },
                modifier = Modifier.padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            )
        }
    }
}

@Composable
fun SelectItem(
    label: String,
    options: Array<out Any?>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    if (options.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .padding(
                    horizontal = SettingsItemsPaddings.Horizontal,
                    vertical = SettingsItemsPaddings.Vertical,
                ),
            label = { Text(text = label) },
            value = options[safeIndex].toString(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded,
                )
            },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
        )

        ExposedDropdownMenu(
            modifier = Modifier.exposedDropdownSize(),
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, text ->
                DropdownMenuItem(
                    text = { Text(text.toString()) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun TriStateItem(
    label: String,
    state: TriState,
    enabled: Boolean = true,
    onClick: ((TriState) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .clickable(
                enabled = enabled && onClick != null,
                onClick = {
                    when (state) {
                        TriState.DISABLED -> onClick?.invoke(TriState.ENABLED_IS)
                        TriState.ENABLED_IS -> onClick?.invoke(TriState.ENABLED_NOT)
                        TriState.ENABLED_NOT -> onClick?.invoke(TriState.DISABLED)
                    }
                },
            )
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
    ) {
        val stateAlpha = if (enabled && onClick != null) 1f else DISABLED_ALPHA

        Icon(
            imageVector = when (state) {
                TriState.DISABLED -> Icons.Rounded.CheckBoxOutlineBlank
                TriState.ENABLED_IS -> Icons.Rounded.CheckBox
                TriState.ENABLED_NOT -> Icons.Rounded.DisabledByDefault
            },
            contentDescription = null,
            tint = if (!enabled || state == TriState.DISABLED) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = stateAlpha)
            } else {
                when (onClick) {
                    null -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                    else -> MaterialTheme.colorScheme.primary
                }
            },
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = stateAlpha),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun TextItem(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = 4.dp),
        label = { Text(text = label) },
        value = value,
        onValueChange = onChange,
        singleLine = true,
    )
}

@Composable
fun SettingsChipRow(labelRes: StringResource, content: @Composable FlowRowScope.() -> Unit) {
    SettingsChipRow(stringResource(labelRes), content)
}

@Composable
fun SettingsChipRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        HeadingItem(label)
        FlowRow(
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                top = 0.dp,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            content = content,
        )
    }
}

@Composable
fun SettingsIconGrid(labelRes: StringResource, content: LazyGridScope.() -> Unit) {
    Column {
        HeadingItem(labelRes)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(128.dp),
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            content = content,
        )
    }
}

@Composable
private fun BaseSettingsItem(
    label: String,
    widget: @Composable RowScope.() -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(
                horizontal = SettingsItemsPaddings.Horizontal,
                vertical = SettingsItemsPaddings.Vertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        widget(this)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
