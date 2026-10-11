package com.plugin.interaction

import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.Header
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.ClearButton
import com.junkfood.seal.ui.component.FilledButtonWithIcon
import com.junkfood.seal.ui.component.OutlinedButtonWithIcon
import com.junkfood.seal.ui.component.OutlinedDismissButton
import com.junkfood.seal.ui.component.SealDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.theme.ErrorTonalPalettes
import com.plugin.interaction.findPluginURLsFromString

/** URL과 저장한 링크를 읽고 같은 입력 단계에 다운로드 설정을 배치합니다. */
@Composable
fun EnhancedInputUrlPage(
    modifier: Modifier = Modifier,
    config: Config,
    onConfigUpdate: (Config) -> Unit,
    settings: @Composable () -> Unit = {},
    initialUrls: List<String> = emptyList(),
    onActionPost: (Action) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val urlList = remember { mutableStateListOf<String>() }
    val savedLinks = remember(config) { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        clipboardManager.getText()?.let {
            urlList.clear()
            urlList.addAll(findPluginURLsFromString(it.toString()))
        }
    }

    LaunchedEffect(config) { savedLinks.clear(); savedLinks.addAll(config.savedLinks) }

    InputUrlPageImpl(
        modifier = modifier,
        urlListFromClipboard = urlList,
        savedLinks = savedLinks,
        onSaveLink = { savedLinks.add(it) },
        onRemoveSavedLink = { savedLinks.remove(it) },
        settings = settings,
        initialUrls = initialUrls,
        onActionPost = onActionPost,
    )

    DisposableEffect(Unit) {
        onDispose { onConfigUpdate(config.copy(savedLinks = savedLinks.toSet())) }
    }
}

@Preview
@Composable
/** InputUrlPreview에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun InputUrlPreview() {
    val urlList = remember {
        mutableStateListOf<String>().apply { repeat(20) { add("https://www.example$it.com/") } }
    }
    InputUrlPageImpl(
        urlListFromClipboard = listOf("https://www.example.com"),
        savedLinks = urlList,
        onSaveLink = { urlList.add(it) },
        onRemoveSavedLink = { urlList.remove(it) },
    ) {}
}

/** 입력·붙여넣기·저장한 링크를 다운로드 설정 화면으로 전달합니다. */
@Composable
private fun InputUrlPageImpl(
    modifier: Modifier = Modifier,
    urlListFromClipboard: List<String>,
    savedLinks: List<String> = emptyList(),
    onSaveLink: (String) -> Unit = {},
    onRemoveSavedLink: (String) -> Unit = {},
    settings: @Composable () -> Unit = {},
    initialUrls: List<String> = emptyList(),
    onActionPost: (Action) -> Unit,
) {

    var url by remember(initialUrls) { mutableStateOf(initialUrls.joinToString("\n")) }
    val parsedUrls = remember(url) { findPluginURLsFromString(url) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var showSavedUrlDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.heightIn(max = 560.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.new_task),
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).padding(horizontal = 32.dp),
            label = { Text(stringResource(R.string.loc_media_link)) },
            maxLines = 3,
            trailingIcon = {
                if (url.isNotEmpty()) {
                    ClearButton { url = "" }
                }
            },
        )


        LazyRow(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 32.dp),
        ) {
            if (urlListFromClipboard.isNotEmpty()) {
                item(key = "paste url") {
                    SuggestionChip(
                        modifier = Modifier.animateItem(),
                        onClick = { url = urlListFromClipboard.first() },
                        label = { Text(stringResource(R.string.paste_msg)) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }

            if (urlListFromClipboard.size > 1) {
                item(key = "paste multiple url") {
                    SuggestionChip(
                        modifier = Modifier.animateItem(),
                        onClick = { showPasteDialog = true },
                        label = {
                            Text(
                                stringResource(
                                    R.string.select_multiple_link,
                                    urlListFromClipboard.size,
                                )
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.ContentPasteGo,
                                contentDescription = null,
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }

            item(key = "saved urls") {
                val addToSavedLinks =
                    parsedUrls.size == 1 && !savedLinks.contains(parsedUrls.single())
                if (addToSavedLinks || savedLinks.isNotEmpty()) {
                    SuggestionChip(
                        modifier = Modifier.animateItem(),
                        onClick = {
                            if (addToSavedLinks) onSaveLink(parsedUrls.single())
                            else {
                                showSavedUrlDialog = true
                            }
                        },
                        label = {
                            Row(modifier = Modifier.animateContentSize()) {
                                if (addToSavedLinks) {
                                    Text(
                                        stringResource(
                                            R.string.add_to,
                                            stringResource(R.string.saved_urls),
                                        )
                                    )
                                } else {
                                    Text(stringResource(R.string.saved_urls))
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.AddLink,
                                contentDescription = null,
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }

        settings()
        }
        Row(
            modifier =
                Modifier.align(Alignment.End).padding(top = 16.dp).padding(horizontal = 20.dp)
        ) {
            OutlinedButtonWithIcon(
                modifier = Modifier.padding(horizontal = 12.dp),
                onClick = { onActionPost(Action.HideSheet) },
                icon = Icons.Outlined.Cancel,
                text = stringResource(R.string.cancel),
            )
            FilledButtonWithIcon(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                text = stringResource(R.string.download),
                enabled = parsedUrls.isNotEmpty(),
            ) {
                onActionPost(Action.ProceedWithURLs(parsedUrls))
            }
        }
    }
    if (showPasteDialog) {
        URLSelectionDialog(
            urlListFromClipboard = urlListFromClipboard,
            onDismissRequest = { showPasteDialog = false },
            onConfirm = { onActionPost(Action.ProceedWithURLs(it)) },
        )
    }

    if (showSavedUrlDialog) {
        SavedUrlDialogImpl(
            urls = savedLinks,
            onRemoveLink = onRemoveSavedLink,
            onActionPost = onActionPost,
            onDismissRequest = { showSavedUrlDialog = false },
        )
    }
}

@Composable
/** URLSelectionDialog에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun URLSelectionDialog(
    modifier: Modifier = Modifier,
    urlListFromClipboard: List<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val indexList =
        remember(urlListFromClipboard) {
            mutableStateListOf<Int>().apply { addAll(urlListFromClipboard.indices) }
        }

    SealDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.select_multiple_link, urlListFromClipboard.size)) },
        icon = { Icon(Icons.Outlined.AddLink, null) },
        confirmButton = {
            FilledButtonWithIcon(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                text = stringResource(R.string.proceed),
                enabled = indexList.isNotEmpty(),
            ) {
                onConfirm(indexList.map { urlListFromClipboard[it] })
                onDismissRequest()
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
        },
        text = {
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                LazyColumn(modifier = Modifier.padding(bottom = 48.dp).heightIn(max = 600.dp)) {
                    itemsIndexed(urlListFromClipboard) { index, url ->
                        DialogCheckBoxItemVariant(text = url, checked = indexList.contains(index)) {
                            if (!it) {
                                indexList -= index
                            } else {
                                indexList += index
                            }
                        }
                    }
                }

                val checkBoxState =
                    remember(indexList.size) {
                        if (indexList.isEmpty()) {
                            ToggleableState.Off
                        } else if (indexList.size < urlListFromClipboard.size) {
                            ToggleableState.Indeterminate
                        } else {
                            ToggleableState.On
                        }
                    }
                Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                    HorizontalDivider(modifier = Modifier)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(48.dp),
                    ) {
                        TriStateCheckbox(
                            state = checkBoxState,
                            onClick = {
                                when (checkBoxState) {
                                    ToggleableState.On -> indexList.clear()
                                    ToggleableState.Off ->
                                        indexList.addAll(urlListFromClipboard.indices)
                                    ToggleableState.Indeterminate -> {
                                        indexList.clear()
                                        indexList.addAll(urlListFromClipboard.indices)
                                    }
                                }
                            },
                        )
                        Text(stringResource(R.string.select_all))
                    }
                }
            }
        },
    )
}

@Composable
/** DialogCheckBoxItemVariant에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun DialogCheckBoxItemVariant(
    modifier: Modifier = Modifier,
    text: String,
    checked: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = true,
                    onValueChange = onValueChange,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                )
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            modifier = Modifier.clearAndSetSemantics {},
            checked = checked,
            onCheckedChange = onValueChange,
            interactionSource = interactionSource,
        )
        Text(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
/** DialogSingleChoiceItemVariant에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun DialogSingleChoiceItemVariant(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = true,
                    onClick = onSelect,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                )
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.clearAndSetSemantics {},
            interactionSource = interactionSource,
        )
        Text(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** SavedURLsDialog에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
@Composable fun SavedURLsDialog(modifier: Modifier = Modifier) {}

@Preview
@Composable
/** SavedUrlPreview에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun SavedUrlPreview() {
    val urls = remember {
        mutableStateListOf<String>().apply { repeat(10) { add("https://www.example$it.com/") } }
    }
    SavedUrlDialogImpl(urls = urls, onActionPost = {}, onRemoveLink = { urls.remove(it) }) {}
}

@Composable
/** SavedUrlDialogImpl에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun SavedUrlDialogImpl(
    modifier: Modifier = Modifier,
    urls: List<String>,
    onRemoveLink: (String) -> Unit,
    onActionPost: (Action) -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (urls.isEmpty()) {
        onDismissRequest()
    }
    var selectedUrl: String? by remember(urls.size) { mutableStateOf(null) }
    val hapticFeedback = LocalHapticFeedback.current

    SealDialog(
        modifier = modifier,
        icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        title = { Text(stringResource(R.string.saved_urls)) },
        onDismissRequest = onDismissRequest,
        dismissButton = { OutlinedDismissButton(onClick = onDismissRequest) },
        confirmButton = {
            FilledButtonWithIcon(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                text = stringResource(R.string.proceed),
                enabled = selectedUrl != null,
            ) {
                onActionPost(Action.ProceedWithURLs(listOf(selectedUrl!!)))
            }
        },
        text = {
            Box(modifier = Modifier.animateContentSize()) {
                HorizontalDivider(Modifier.align(Alignment.TopCenter).zIndex(1f))
                HorizontalDivider(Modifier.align(Alignment.BottomCenter).zIndex(1f))

                LazyColumn(modifier = Modifier.heightIn(max = 600.dp)) {
                    items(items = urls, key = { it }) {
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            when (dismissState.currentValue) {
                                SwipeToDismissBoxValue.EndToStart -> {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onRemoveLink(it)
                                }
                                else -> {}
                            }
                        }
                        val containerColor by
                            animateColorAsState(
                                when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.EndToStart ->
                                        ErrorTonalPalettes.accent1(80.0)
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            )
                        val contentColor by
                            animateColorAsState(
                                when (dismissState.targetValue) {
                                    SwipeToDismissBoxValue.Settled ->
                                        MaterialTheme.colorScheme.onSurface
                                    else -> ErrorTonalPalettes.accent1(10.0)
                                }
                            )
                        SwipeToDismissBox(
                            modifier = Modifier.animateItem(),
                            state = dismissState,
                            enableDismissFromEndToStart = true,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().background(containerColor)) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        null,
                                        modifier =
                                            Modifier.align(Alignment.CenterEnd)
                                                .padding(end = 16.dp)
                                                .size(28.dp),
                                        tint = contentColor,
                                    )
                                }
                            },
                        ) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                DialogSingleChoiceItemVariant(
                                    text = it,
                                    selected = selectedUrl == it,
                                    onSelect = { selectedUrl = it },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
