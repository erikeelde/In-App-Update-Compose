package se.warting.inappupdate.compose

import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.ktx.AppUpdateResult
import kotlinx.coroutines.launch

// Try launch update in another way
private const val APP_UPDATE_REQUEST_CODE = 86500

@Composable
public fun RequireLatestVersion(content: @Composable () -> Unit) {
    val inAppUpdateState: InAppUpdateState = rememberInAppUpdateState()
    val scope = rememberCoroutineScope()
    when (val state = inAppUpdateState) {
        is InAppUpdateState.DownloadedUpdate -> {
            UpdateDownloadedView {
                scope.launch {
                    state.appUpdateResult.completeUpdate()
                }
            }
        }
        is InAppUpdateState.InProgressUpdate -> {
            UpdateInProgress(
                progress = state.appUpdateResult.installState.bytesDownloaded()
                    .toFloat() / state.appUpdateResult.installState.totalBytesToDownload().toFloat()
            )
        }
        InAppUpdateState.Loading -> {
            CircularProgressIndicator()
        }
        InAppUpdateState.NotAvailable -> content()
        is InAppUpdateState.OptionalUpdate -> {
            Text("OptionalUpdate")
        }
        is InAppUpdateState.RequiredUpdate -> {
            Text("RequiredUpdate")
        }
    }
}
