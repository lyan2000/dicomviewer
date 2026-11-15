package com.tauslop.dicomviewer

import android.content.Intent
import android.net.Uri
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
                        keepOnlyGrant(uri)
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
                        onTool = viewModel::selectTool,
                        onPreset = viewModel::applyPreset,
                        onWindowDrag = viewModel::dragWindow,
                        onCancelScan = viewModel::cancelScan,
                        onViewSize = viewModel::onViewSize,
                        onSwap = viewModel::swapMain,
                        onSwap3D = viewModel::swapTo3D,
                        onVrPreset = viewModel::applyVrPreset,
                        onVrMode = viewModel::setVrMode,
                        onView3D = viewModel::view3D,
                        onToggleShading = viewModel::toggleVrShading,
                        onRotate3D = viewModel::rotate3D,
                        onTapImage = viewModel::tapAt,
                        onTwist = viewModel::twist,
                        onResetPlanes = viewModel::resetPlanes,
                    )
                }
                ViewerScreen(state, actions)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.saveSession()
    }

    /** Persist read access to [uri] across restarts, releasing grants for folders no longer used. */
    private fun keepOnlyGrant(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        contentResolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach { runCatching { contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }
}
