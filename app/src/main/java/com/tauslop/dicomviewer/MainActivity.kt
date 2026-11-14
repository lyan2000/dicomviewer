package com.tauslop.dicomviewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tauslop.dicomviewer.viewer.ViewerActions
import com.tauslop.dicomviewer.viewer.ViewerScreen
import com.tauslop.dicomviewer.viewer.ViewerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        viewModel.openFolder(uri)
                    }
                }
                val actions = remember {
                    ViewerActions(
                        onOpenFolder = { picker.launch(null) },
                        onSelectSeries = viewModel::selectSeries,
                        onSelectSlice = viewModel::showSlice,
                        onScroll = viewModel::scrollBy,
                        onTransform = viewModel::setTransform,
                        onResetView = viewModel::resetView,
                        onResetAll = viewModel::resetAll,
                        onToggleWl = viewModel::toggleWlMode,
                        onPreset = viewModel::applyPreset,
                        onWindowDrag = viewModel::dragWindow,
                    )
                }
                ViewerScreen(state, actions)
            }
        }
    }
}
