package eu.kanade.tachiyomi.ui.browse.extension

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

class NovelExtensionReposScreen(
    private val url: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelExtensionReposScreenModel() }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(url) {
            val repoUrl = url ?: return@LaunchedEffect
            screenModel.createJsRepo(repoUrl, repoUrl)
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.label_extension_repos),
                    navigateUp = navigator::pop,
                    actions = {
                        androidx.compose.material3.IconButton(onClick = screenModel::refreshRepos) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(MR.strings.action_webview_refresh),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { screenModel.showDialog(NovelRepoDialog.ChooseType) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(MR.strings.action_add),
                    )
                }
            },
        ) { contentPadding ->
            if (state is NovelRepoScreenState.Loading) {
                LoadingScreen()
                return@Scaffold
            }

            val successState = state as NovelRepoScreenState.Success

            if (successState.isEmpty) {
                EmptyScreen(
                    stringRes = MR.strings.information_empty_repos,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                LazyColumn(
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    if (successState.jsRepos.isNotEmpty()) {
                        item(key = "js-header") {
                            Text(
                                text = stringResource(TDMR.strings.novel_ext_repos_js_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    horizontal = MaterialTheme.padding.medium,
                                    vertical = MaterialTheme.padding.small,
                                ),
                            )
                        }
                        items(
                            items = successState.jsRepos,
                            key = { "js-${it.url}" },
                        ) { repo ->
                            NovelRepoListItem(
                                repo = repo,
                                onSetEnabled = { enabled -> screenModel.setJsRepoEnabled(repo.url, enabled) },
                                onDelete = { screenModel.showDialog(NovelRepoDialog.DeleteJs(repo)) },
                            )
                        }
                    }

                    if (successState.kotlinRepos.isNotEmpty()) {
                        item(key = "kotlin-header") {
                            Text(
                                text = stringResource(TDMR.strings.novel_ext_repos_kt_header),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    horizontal = MaterialTheme.padding.medium,
                                    vertical = MaterialTheme.padding.small,
                                ),
                            )
                        }
                        items(
                            items = successState.kotlinRepos.toList(),
                            key = { "kotlin-${it.baseUrl}" },
                        ) { repo ->
                            KotlinRepoListItem(
                                repo = repo,
                                enabled = repo.baseUrl !in successState.disabledKotlinRepos,
                                onSetEnabled = { enabled -> screenModel.setKotlinRepoEnabled(repo.baseUrl, enabled) },
                                onDelete = { screenModel.showDialog(NovelRepoDialog.DeleteKotlin(repo.baseUrl)) },
                            )
                        }
                    }
                }
            }

            when (val dialog = successState.dialog) {
                null -> {}
                is NovelRepoDialog.ChooseType -> {
                    RepoTypeChooserDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        onChooseJs = {
                            screenModel.dismissDialog()
                            screenModel.showDialog(NovelRepoDialog.CreateJs)
                        },
                        onChooseKotlin = {
                            screenModel.dismissDialog()
                            screenModel.showDialog(NovelRepoDialog.CreateKotlin)
                        },
                    )
                }
                is NovelRepoDialog.CreateJs -> {
                    NovelRepoCreateDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        onCreate = { name, url -> screenModel.createJsRepo(name, url) },
                    )
                }
                is NovelRepoDialog.CreateKotlin -> {
                    KotlinRepoCreateDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        onCreate = { url -> screenModel.createKotlinRepo(url) },
                    )
                }
                is NovelRepoDialog.DeleteJs -> {
                    NovelRepoDeleteDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        onDelete = { screenModel.deleteJsRepo(dialog.repo.url) },
                        repoName = dialog.repo.name,
                    )
                }
                is NovelRepoDialog.DeleteKotlin -> {
                    NovelRepoDeleteDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        onDelete = { screenModel.deleteKotlinRepo(dialog.baseUrl) },
                        repoName = dialog.baseUrl,
                    )
                }
                is NovelRepoDialog.KotlinConflict -> {
                    KotlinRepoConflictDialog(
                        onDismissRequest = screenModel::dismissDialog,
                        onReplace = { screenModel.replaceKotlinRepo(dialog.newRepo) },
                        oldRepo = dialog.oldRepo,
                        newRepo = dialog.newRepo,
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelRepoListItem(
    repo: JsPluginRepository,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    ElevatedCard(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.padding.medium,
                    top = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = null)
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = repo.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repo.url))
                context.startActivity(intent)
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                )
            }

            IconButton(
                onClick = {
                    context.copyToClipboard(repo.url, repo.url)
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }

            Row(
                modifier = Modifier.padding(
                    top = MaterialTheme.padding.small,
                    end = MaterialTheme.padding.small,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = repo.enabled,
                    onCheckedChange = onSetEnabled,
                )
            }
        }
    }
}

@Composable
private fun NovelRepoCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_add_repo)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = "Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(text = "URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.ifBlank { url }, url) },
                enabled = url.isNotBlank(),
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun NovelRepoDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    repoName: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_delete)) },
        text = { Text(text = stringResource(MR.strings.delete_repo_confirmation, repoName)) },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text(text = stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun KotlinRepoListItem(
    repo: mihon.domain.extensionrepo.model.ExtensionRepo,
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    ElevatedCard(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.padding.medium,
                    top = MaterialTheme.padding.medium,
                    end = MaterialTheme.padding.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.Label, contentDescription = null)
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = repo.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = repo.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repo.baseUrl))
                context.startActivity(intent)
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                )
            }

            IconButton(
                onClick = {
                    context.copyToClipboard(repo.baseUrl, repo.baseUrl)
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                )
            }

            Row(
                modifier = Modifier.padding(
                    top = MaterialTheme.padding.small,
                    end = MaterialTheme.padding.small,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = onSetEnabled,
                )
            }
        }
    }
}

@Composable
private fun KotlinRepoCreateDialog(
    onDismissRequest: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Add Kotlin extension repo") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(text = "Repository URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(url) },
                enabled = url.isNotBlank(),
            ) {
                Text(text = stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun KotlinRepoConflictDialog(
    onDismissRequest: () -> Unit,
    onReplace: () -> Unit,
    oldRepo: mihon.domain.extensionrepo.model.ExtensionRepo,
    newRepo: mihon.domain.extensionrepo.model.ExtensionRepo,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Repository conflict") },
        text = {
            Text(
                text = "A repository with the same signing key already exists (${oldRepo.name}). " +
                    "Replace it with ${newRepo.name}?",
            )
        },
        confirmButton = {
            TextButton(onClick = onReplace) {
                Text(text = "Replace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun RepoTypeChooserDialog(
    onDismissRequest: () -> Unit,
    onChooseJs: () -> Unit,
    onChooseKotlin: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Add Repository") },
        text = { Text(text = "Choose the type of repository to add:") },
        confirmButton = {
            TextButton(onClick = onChooseKotlin) {
                Text(text = "Kotlin Extension")
            }
        },
        dismissButton = {
            TextButton(onClick = onChooseJs) {
                Text(text = "JS Plugin")
            }
        },
    )
}
