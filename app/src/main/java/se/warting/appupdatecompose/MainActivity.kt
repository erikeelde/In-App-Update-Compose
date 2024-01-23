package se.warting.appupdatecompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.play.core.ktx.AppUpdateResult
import kotlinx.coroutines.launch
import se.warting.inappupdate.compose.InAppUpdateState
import se.warting.inappupdate.compose.rememberInAppUpdateState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // A surface container using the 'background' color from the theme
                Surface(color = MaterialTheme.colors.background) {
                    InAppUpdate()
                }
            }
        }
    }
}

@Composable
fun InAppUpdate() {
    when (val updateState: InAppUpdateState = rememberInAppUpdateState()) {
//        is AppUpdateResult.NotAvailable -> NotAvailable()
//        is AppUpdateResult.Available -> Available(result) {
//            updateState.update(it, result)
//        }
//
//        is AppUpdateResult.InProgress -> InProgress(result)
//        is AppUpdateResult.Downloaded -> Downloaded(result)
        is InAppUpdateState.DownloadedUpdate -> {
            Text("DownloadedUpdate")
        }

        is InAppUpdateState.InProgressUpdate -> {
            Text("InProgressUpdate")
        }

        InAppUpdateState.Loading -> {
            Text("Loading")
        }

        InAppUpdateState.NotAvailable -> {
            Text("NotAvailable")
        }

        is InAppUpdateState.OptionalUpdate -> {
            OptionUpdateAvailable(updateState)
        }

        is InAppUpdateState.RequiredUpdate -> {

            RequiredUpdateAvailable(updateState)
            Text("RequiredUpdate")
        }
    }
}

@Composable
fun NotAvailable() {
    Text(text = "NotAvailable")
}

@Composable
fun OptionUpdateAvailable(result: InAppUpdateState.OptionalUpdate) {
    Column {
        Text(
            text = "App update available.\n"
//                    +
//                    "Versioncode: " + appUpdateResult.updateInfo.availableVersionCode() +
//                    "\nSince since: " + appUpdateResult.updateInfo.clientVersionStalenessDays +
//                    " days"
        )


        Button(onClick = { result.onStartUpdate() }) {
            Text(text = "Start Immediate Update")
        }
//        Button(onClick = { update(Mode.FLEXIBLE) }) {
//            Text(text = "Start flexible update")
//        }
    }
}

@Composable
fun RequiredUpdateAvailable(appUpdateResult: InAppUpdateState.RequiredUpdate) {
    Column {
        Text(
            text = "App update available.\n"
//                    +
//                    "Versioncode: " + appUpdateResult.updateInfo.availableVersionCode() +
//                    "\nSince since: " + appUpdateResult.updateInfo.clientVersionStalenessDays +
//                    " days"
        )

        Button(onClick = {
            appUpdateResult.onStartUpdate()
        }) {
            Text(text = "Start Immediate Update")
        }
    }
}

@Composable
fun InProgress(appUpdateResult: AppUpdateResult.InProgress) {
    Text(text = "InProgress, downloaded: " + appUpdateResult.installState.bytesDownloaded())
}

@Composable
fun Downloaded(appUpdateResult: AppUpdateResult.Downloaded) {
    val scope = rememberCoroutineScope()
    Button(onClick = {
        scope.launch {
            appUpdateResult.completeUpdate()
        }
    }) {
        Text(text = "Install")
    }
}
