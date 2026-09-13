package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Top app bar region of [ModelRunScreen]: the collapsing title, the back
 * navigation (gated on an in-flight generation), and the prompt/result/history
 * tab buttons.
 *
 * State is read from the holders *inside* this composable so tab switches
 * ([PagerState] page changes) and the running flag don't recompose the screen
 * orchestrator. [scrollBehavior] is created by the screen (its
 * nestedScrollConnection wires the Scaffold) and passed in.
 */
@Composable
internal fun RunTopBar(
    runState: RunGenerationState,
    setupState: RunSetupState,
    model: Model?,
    pagerState: PagerState,
    scrollBehavior: TopAppBarScrollBehavior,
    coroutineScope: CoroutineScope,
    onExit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    LargeTopAppBar(
        title = {
            // Hide title when collapsed
            if (scrollBehavior.state.collapsedFraction < 0.5f) {
                Column {
                    Text(
                        text = model?.name ?: "Running Model",
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    Text(
                        text = model?.description ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = {
                if (runState.isRunning) {
                    setupState.showInterruptDialog = true
                } else {
                    onExit()
                }
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scrollBehavior = scrollBehavior,
        actions = {
            Row {
                val tabs = listOf(
                    stringResource(R.string.prompt_tab),
                    stringResource(R.string.result_tab),
                    stringResource(R.string.history_tab),
                )
                tabs.forEachIndexed { index, label ->
                    val selected = pagerState.currentPage == index
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                focusManager.clearFocus()
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        ),
                    ) {
                        Text(label)
                    }
                }
            }
        },
    )
}
