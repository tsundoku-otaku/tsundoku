package eu.kanade.presentation.reader.appbars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

private val readerBarsSlideAnimationSpec = tween<IntOffset>(200)
private val readerBarsFadeAnimationSpec = tween<Float>(150)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelReaderAppBars(
    visible: Boolean,

    // Top bar
    novelTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    onClickTopAppBar: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onReloadLocal: () -> Unit,
    onReloadSource: () -> Unit,
    onEditBottomBar: () -> Unit,

    // Progress slider
    showProgressSlider: Boolean,
    currentProgress: Int, // 0-100 percentage
    onProgressChange: (Int) -> Unit,

    // Bottom bar - navigation
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,

    // Bottom bar - actions
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    onClickSettings: () -> Unit,
    onScrollToTop: () -> Unit,
    isAutoScrolling: Boolean,
    onToggleAutoScroll: () -> Unit,
    isTranslating: Boolean,
    onToggleTranslation: () -> Unit,
    onLongPressTranslation: () -> Unit,
    onRetranslate: (() -> Unit)? = null,
    isTtsActive: Boolean,
    isTtsPaused: Boolean,
    onToggleTts: () -> Unit,
    onLongPressTts: () -> Unit,

    isEditing: Boolean = false,
    onToggleEdit: () -> Unit = {},
    isWebView: Boolean = true,
    onQuotes: () -> Unit,

    // Toolbar customization
    bottomBarItems: List<BottomBarItemState>,
) {
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)

    Column(modifier = Modifier.fillMaxHeight()) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec),
        ) {
            NovelReaderTopBar(
                modifier = Modifier
                    .background(backgroundColor)
                    .clickable(onClick = onClickTopAppBar),
                novelTitle = novelTitle,
                chapterTitle = chapterTitle,
                navigateUp = navigateUp,
                bookmarked = bookmarked,
                onToggleBookmarked = onToggleBookmarked,
                onOpenInWebView = onOpenInWebView,
                onOpenInBrowser = onOpenInBrowser,
                onShare = onShare,
                onReloadLocal = onReloadLocal,
                onReloadSource = onReloadSource,
                onEditBottomBar = onEditBottomBar,
                onRetranslate = onRetranslate,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeIn(animationSpec = readerBarsFadeAnimationSpec),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = readerBarsSlideAnimationSpec) +
                fadeOut(animationSpec = readerBarsFadeAnimationSpec),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                // Progress slider (above bottom bar)
                if (showProgressSlider) {
                    NovelProgressSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.medium),
                        currentProgress = currentProgress,
                        onProgressChange = onProgressChange,
                        backgroundColor = backgroundColor,
                    )
                }

                NovelReaderBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.small),
                    items = bottomBarItems,
                    onNextChapter = onNextChapter,
                    enabledNext = enabledNext,
                    onPreviousChapter = onPreviousChapter,
                    enabledPrevious = enabledPrevious,
                    orientation = orientation,
                    onClickOrientation = onClickOrientation,
                    onClickSettings = onClickSettings,
                    onScrollToTop = onScrollToTop,
                    isAutoScrolling = isAutoScrolling,
                    onToggleAutoScroll = onToggleAutoScroll,
                    isTranslating = isTranslating,
                    onToggleTranslation = onToggleTranslation,
                    onLongPressTranslation = onLongPressTranslation,
                    isTtsActive = isTtsActive,
                    isTtsPaused = isTtsPaused,
                    onToggleTts = onToggleTts,
                    onLongPressTts = onLongPressTts,
                    isEditing = isEditing,
                    isWebView = isWebView,
                    onToggleEdit = onToggleEdit,
                    onQuotes = onQuotes,
                )
            }
        }
    }
}

@Composable
private fun NovelReaderTopBar(
    novelTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onReloadLocal: () -> Unit,
    onReloadSource: () -> Unit,
    onEditBottomBar: () -> Unit,
    onRetranslate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = novelTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        add(
                            AppBar.Action(
                                title = stringResource(
                                    if (bookmarked) {
                                        MR.strings.action_remove_bookmark
                                    } else {
                                        MR.strings.action_bookmark
                                    },
                                ),
                                icon = if (bookmarked) {
                                    Icons.Outlined.Bookmark
                                } else {
                                    Icons.Outlined.BookmarkBorder
                                },
                                onClick = onToggleBookmarked,
                            ),
                        )
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_reload_local),
                                onClick = onReloadLocal,
                            ),
                        )
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_reload_source),
                                onClick = onReloadSource,
                            ),
                        )
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(TDMR.strings.action_edit_appbar),
                                onClick = onEditBottomBar,
                            ),
                        )
                        onOpenInWebView?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_web_view),
                                    onClick = it,
                                ),
                            )
                        }
                        onOpenInBrowser?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_browser),
                                    onClick = it,
                                ),
                            )
                        }
                        onShare?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_share),
                                    onClick = it,
                                ),
                            )
                        }
                        onRetranslate?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(TDMR.strings.action_retranslate),
                                    onClick = it,
                                ),
                            )
                        }
                    }
                    .build(),
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelReaderBottomBar(
    items: List<BottomBarItemState>,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    onClickSettings: () -> Unit,
    onScrollToTop: () -> Unit,
    isAutoScrolling: Boolean,
    onToggleAutoScroll: () -> Unit,
    isTranslating: Boolean,
    onToggleTranslation: () -> Unit,
    onLongPressTranslation: () -> Unit,
    isTtsActive: Boolean,
    isTtsPaused: Boolean,
    onToggleTts: () -> Unit,
    onLongPressTts: () -> Unit,
    isEditing: Boolean,
    isWebView: Boolean,
    onToggleEdit: () -> Unit,
    onQuotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabledItems = remember(items, isWebView) {
        items.filter { it.enabled && (isWebView || it.item != BottomBarItem.EDIT) }
    }

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth
        // Calculate icon size based on available width and number of items
        // Standard IconButton is 48dp, but we can scale down to 36dp minimum
        val itemCount = enabledItems.size
        val idealTotalWidth = (itemCount * 48).dp
        val scaleFactor = if (idealTotalWidth > availableWidth) {
            (availableWidth / idealTotalWidth).coerceIn(0.75f, 1f)
        } else {
            1f
        }
        val iconSize = (24 * scaleFactor).dp
        val buttonSize = (48 * scaleFactor).dp
        val paddingSize = (4 * scaleFactor).dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            enabledItems.forEach { itemState ->
                when (itemState.item) {
                    BottomBarItem.PREV_CHAPTER -> IconButton(
                        onClick = onPreviousChapter,
                        enabled = enabledPrevious,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.NavigateBefore,
                            contentDescription = stringResource(MR.strings.action_previous_chapter),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    BottomBarItem.NEXT_CHAPTER -> IconButton(
                        onClick = onNextChapter,
                        enabled = enabledNext,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.NavigateNext,
                            contentDescription = stringResource(MR.strings.action_next_chapter),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Scroll to top
                    BottomBarItem.SCROLL_TO_TOP -> IconButton(
                        onClick = onScrollToTop,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.VerticalAlignTop,
                            contentDescription = stringResource(TDMR.strings.action_scroll_to_top),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Translation toggle - tap for quick translate, long-press for language picker
                    BottomBarItem.TRANSLATE -> androidx.compose.material3.Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .padding(paddingSize),
                        shape = MaterialTheme.shapes.small,
                        color = if (isTranslating) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = onToggleTranslation,
                                onLongClick = onLongPressTranslation,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = stringResource(TDMR.strings.action_translate),
                                tint = if (isTranslating) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    }

                    // Auto-scroll toggle
                    BottomBarItem.AUTO_SCROLL -> IconButton(
                        onClick = onToggleAutoScroll,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            imageVector = if (isAutoScrolling) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(
                                if (isAutoScrolling) {
                                    TDMR.strings.action_stop_auto_scroll
                                } else {
                                    TDMR.strings.action_start_auto_scroll
                                },
                            ),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // TTS toggle - tap to play/pause, long press to stop
                    BottomBarItem.TTS -> Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .combinedClickable(
                                onClick = onToggleTts,
                                onLongClick = onLongPressTts,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                isTtsActive && !isTtsPaused -> Icons.Outlined.Pause
                                isTtsActive && isTtsPaused -> Icons.Outlined.PlayArrow
                                else -> Icons.Outlined.RecordVoiceOver
                            },
                            contentDescription = stringResource(TDMR.strings.pref_novel_tts),
                            tint = if (isTtsActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Orientation
                    BottomBarItem.ORIENTATION -> IconButton(
                        onClick = onClickOrientation,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            orientation.icon,
                            contentDescription = stringResource(MR.strings.rotation_type),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Settings
                    BottomBarItem.SETTINGS -> IconButton(
                        onClick = onClickSettings,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(MR.strings.action_settings),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Quotes
                    BottomBarItem.QUOTES -> IconButton(
                        onClick = onQuotes,
                        modifier = Modifier.size(buttonSize),
                    ) {
                        Icon(
                            Icons.Outlined.FormatQuote,
                            contentDescription = stringResource(TDMR.strings.action_quotes),
                            modifier = Modifier.size(iconSize),
                        )
                    }

                    // Edit
                    BottomBarItem.EDIT -> androidx.compose.material3.Surface(
                        modifier = Modifier
                            .size(buttonSize)
                            .padding(paddingSize),
                        shape = MaterialTheme.shapes.small,
                        color = if (isEditing) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        border = if (isEditing) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        IconButton(onClick = onToggleEdit) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit",
                                tint = if (isEditing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(iconSize),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Resolves display icon + label per item — keeps the when() out of the sheet
@Composable
internal fun bottomBarItemInfo(
    item: BottomBarItem,
    orientation: ReaderOrientation,
    isAutoScrolling: Boolean,
    isTtsActive: Boolean,
    isTtsPaused: Boolean,
): Pair<ImageVector, String> = when (item) {
    BottomBarItem.PREV_CHAPTER -> Icons.AutoMirrored.Outlined.NavigateBefore to stringResource(MR.strings.action_previous_chapter)
    BottomBarItem.NEXT_CHAPTER -> Icons.AutoMirrored.Outlined.NavigateNext to stringResource(MR.strings.action_next_chapter)
    BottomBarItem.SCROLL_TO_TOP -> Icons.Outlined.VerticalAlignTop to stringResource(TDMR.strings.action_scroll_to_top)
    BottomBarItem.TRANSLATE -> Icons.Outlined.Translate to stringResource(TDMR.strings.action_translate)
    BottomBarItem.AUTO_SCROLL -> Icons.Outlined.PlayArrow to stringResource(TDMR.strings.action_start_auto_scroll)
    BottomBarItem.TTS -> Icons.Outlined.RecordVoiceOver to stringResource(TDMR.strings.pref_novel_tts)
    BottomBarItem.QUOTES -> Icons.Outlined.FormatQuote to stringResource(TDMR.strings.action_quotes)
    BottomBarItem.ORIENTATION -> orientation.icon to stringResource(MR.strings.rotation_type)
    BottomBarItem.SETTINGS -> Icons.Outlined.Settings to stringResource(MR.strings.action_settings)
    BottomBarItem.EDIT -> Icons.Outlined.Edit to stringResource(MR.strings.action_edit)
}

@Composable
private fun NovelProgressSlider(
    currentProgress: Int,
    onProgressChange: (Int) -> Unit,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val sliderDragged by interactionSource.collectIsDraggedAsState()

    LaunchedEffect(currentProgress) {
        if (sliderDragged) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Current progress percentage
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(text = "$currentProgress%")
            // Taking up full length so the slider doesn't shift
            Text(text = "100%", color = Color.Transparent)
        }

        Slider(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            value = currentProgress,
            valueRange = 0..100,
            onValueChange = { newProgress ->
                if (newProgress != currentProgress) {
                    onProgressChange(newProgress)
                }
            },
            interactionSource = interactionSource,
        )

        Text(text = "100%")
    }
}
