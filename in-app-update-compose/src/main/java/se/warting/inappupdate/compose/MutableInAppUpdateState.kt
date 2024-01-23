package se.warting.inappupdate.compose

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.common.IntentSenderForResultStarter
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.isImmediateUpdateAllowed
import com.google.android.play.core.ktx.requestUpdateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import com.google.android.play.core.install.model.ActivityResult as UpdateActivityResult

// High-priority updates are 4-5
private const val DEFAULT_HIGH_PRIORITIZE_UPDATE = 4

// Medium-priority updates are 2-3
private const val DEFAULT_MEDIUM_PRIORITIZE_UPDATE = 2

// For medium-priority updates, prompt once per day.
private const val DEFAULT_PROMPT_INTERVAL_MEDIUM_PRIORITIZE_UPDATE_IN_DAYS = 1

// For low-priority updates, prompt once per week.
private const val DEFAULT_PROMPT_INTERVAL_LOW_PRIORITIZE_UPDATE_IN_DAYS = 7

private const val LOGTAG = "InAppUpdate"

public interface InAppUpdateSettings {

    public val updateDeclined: StateFlow<Declined>
    public fun decline(declined: Declined)

    public val highPrioritizeUpdates: Int
        get() = DEFAULT_HIGH_PRIORITIZE_UPDATE
    public val mediumPrioritizeUpdates: Int
        get() = DEFAULT_MEDIUM_PRIORITIZE_UPDATE
    public val promptIntervalMediumPrioritizeUpdateInDays: Int
        get() = DEFAULT_PROMPT_INTERVAL_MEDIUM_PRIORITIZE_UPDATE_IN_DAYS

    public val promptIntervalLowPrioritizeUpdateInDays: Int
        get() = DEFAULT_PROMPT_INTERVAL_LOW_PRIORITIZE_UPDATE_IN_DAYS
}

public data class Declined(val version: Int, val date: Instant)

private class InMemoryInAppUpdateSettings : InAppUpdateSettings {
    private val _updateDeclined: MutableStateFlow<Declined> =
        MutableStateFlow(Declined(0, Instant.DISTANT_PAST))


    override val updateDeclined: StateFlow<Declined> = _updateDeclined.asStateFlow()
    override fun decline(declined: Declined) {
        _updateDeclined.update {
            declined
        }
    }
}

@Composable
internal fun rememberMutableInAppUpdateState(): InAppUpdateState {
    return rememberMutableInAppUpdateState(remember {
        InMemoryInAppUpdateSettings()
    })
}

@Composable
internal fun rememberMutableInAppUpdateState(settings: InAppUpdateSettings): InAppUpdateState {

    val existingAppUpdateManager: AppUpdateManager? = LocalAppUpdateManager.current
    val context = LocalContext.current

    val appUpdateManager: AppUpdateManager = remember {
        existingAppUpdateManager ?: AppUpdateManagerFactory.create(context)
    }


    var onResultState: (ActivityResult) -> Unit by remember { mutableStateOf({}) }
    val intentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
        onResult = { onResultState(it) }
    )

    var recomposeKey by remember {
        mutableIntStateOf(0)
    }

    val inAppUpdateState = remember {
        MutableInAppUpdateState(
            appUpdateManager = appUpdateManager,
            produceIntentLauncher = { onResult ->
                onResultState = onResult
                intentLauncher
            },
            recompose = {
                recomposeKey++
            },
            settings = settings
        )
    }

    val state: InAppUpdateState = produceState<InAppUpdateState>(
        initialValue = InAppUpdateState.Loading,
        key1 = recomposeKey
    ) {
        combine(
            appUpdateManager.requestUpdateFlow().catch { error ->
                logcat(
                    priority = LogPriority.ERROR,
                    tag = LOGTAG,
                ) { "UpdaterState: Error in update flow. " + error.asLog() }
                AppUpdateResult.NotAvailable
            },
            settings.updateDeclined,
            ::Pair
        ).collect { (appUpdateResult, declined) ->
            value = appUpdateResult.toInAppUpdateState(
                settings = settings,
                declined = declined,
                onStartUpdate = { updateInfo, updateType ->
                    logcat(
                        priority = LogPriority.INFO,
                        tag = LOGTAG,
                    ) {
                        "UpdaterState: Starting update of type " + describeUpdateType(
                            updateType
                        )
                    }
                    inAppUpdateState.startUpdate(updateInfo, updateType)
                },
                onDeclineUpdate = { updateInfo ->
                    logcat(
                        priority = LogPriority.INFO,
                        tag = LOGTAG,
                    ) {
                        "UpdaterState: Declined flexible update"
                    }
                    inAppUpdateState.declineUpdate(updateInfo)
                },
                onCompleteUpdate = { result ->
                    logcat(
                        priority = LogPriority.INFO,
                        tag = LOGTAG,
                    ) {
                        "UpdaterState: Completing flexible update"
                    }
                    result.completeUpdate()
                }
            )
        }
    }.value

    return state
}


private fun describeUpdateType(updateType: Mode): String = when (updateType) {
    Mode.IMMEDIATE -> "IMMEDIATE"
    Mode.FLEXIBLE -> "FLEXIBLE"
}

public sealed class InAppUpdateState {
    public data object Loading : InAppUpdateState()
    public data object NotAvailable : InAppUpdateState()
    public data class RequiredUpdate(val onStartUpdate: () -> Unit) : InAppUpdateState()
    public data class OptionalUpdate(
        val onStartUpdate: () -> Unit,
        val onDeclineUpdate: () -> Unit,
        val shouldPrompt: Boolean
    ) : InAppUpdateState()

    public data class DownloadedUpdate(
        val appUpdateResult: AppUpdateResult.Downloaded,
        val onCompleteUpdate: suspend (AppUpdateResult.Downloaded) -> Unit
    ) : InAppUpdateState()

    public data class InProgressUpdate(
        val appUpdateResult: AppUpdateResult.InProgress,
    ) : InAppUpdateState()
}

internal class MutableInAppUpdateState(
    private val appUpdateManager: AppUpdateManager,
    private val produceIntentLauncher: (onResult: (ActivityResult) -> Unit) ->
    ActivityResultLauncher<IntentSenderRequest>,
    private val recompose: () -> Unit,
    private val settings: InAppUpdateSettings,
) {
    internal fun startUpdate(updateInfo: AppUpdateInfo, mode: Mode) {

        val type: AppUpdateOptions = when (mode) {
            Mode.FLEXIBLE -> AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
            Mode.IMMEDIATE -> AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        }

        appUpdateManager.startUpdateFlowForResult(
            updateInfo,
            produceIntentLauncher { result ->
                handleUpdateFlowResult(updateInfo, result)
            }.starter(),
            type,
            0
        )
    }

    private fun handleUpdateFlowResult(updateInfo: AppUpdateInfo, updateResult: ActivityResult) {
        when (updateResult.resultCode) {
            Activity.RESULT_OK -> {
                logcat(
                    priority = LogPriority.INFO,
                    tag = LOGTAG,
                ) {
                    "UpdaterState: result OK"
                }
            }

            Activity.RESULT_CANCELED -> {
                //log.info("UpdaterState: result CANCELED", emptyBreadcrumb)
                logcat(
                    priority = LogPriority.INFO,
                    tag = LOGTAG,
                ) {
                    "UpdaterState: result CANCELED"
                }
                declineUpdate(updateInfo)
            }

            UpdateActivityResult.RESULT_IN_APP_UPDATE_FAILED -> {
                logcat(
                    priority = LogPriority.INFO,
                    tag = LOGTAG,
                ) {
                    "UpdaterState: result FAILED"
                }
            }
        }
        // Changing the key restarts the flow in `update`, creating a new subscription to
        // AppUpdateManager.requestUpdateFlow() and causing readers of that state to recompose.
        recompose()
    }


    internal fun declineUpdate(updateInfo: AppUpdateInfo) {
        // Don't store declined state for high-priority updates.
        if (updateInfo.updatePriority() >= settings.highPrioritizeUpdates) return
        settings.decline(Declined(updateInfo.availableVersionCode(), Clock.System.now()))
    }
}


internal fun ActivityResultLauncher<IntentSenderRequest>.starter(): IntentSenderForResultStarter =
    IntentSenderForResultStarter { intentSender, _, fillInIntent, flagsMask, flagsValue, _, _ ->
        launch(
            IntentSenderRequest.Builder(intentSender)
                .setFillInIntent(fillInIntent)
                .setFlags(flagsValue, flagsMask)
                .build()
        )
    }


private fun AppUpdateResult.toInAppUpdateState(
    settings: InAppUpdateSettings,
    declined: Declined,
    onStartUpdate: (updateInfo: AppUpdateInfo, updateType: Mode) -> Unit,
    onDeclineUpdate: (AppUpdateInfo) -> Unit,
    onCompleteUpdate: suspend (AppUpdateResult.Downloaded) -> Unit
): InAppUpdateState = when (this) {
    AppUpdateResult.NotAvailable -> InAppUpdateState.NotAvailable
    is AppUpdateResult.Available -> if (shouldUpdateImmediately(updateInfo, settings)) {
        InAppUpdateState.RequiredUpdate { onStartUpdate(updateInfo, Mode.IMMEDIATE) }
    } else {
        InAppUpdateState.OptionalUpdate(
            onStartUpdate = { onStartUpdate(updateInfo, Mode.FLEXIBLE) },
            onDeclineUpdate = { onDeclineUpdate(updateInfo) },
            shouldPrompt = shouldPromptToUpdate(declined, updateInfo, settings)
        )
    }

    is AppUpdateResult.Downloaded -> InAppUpdateState.DownloadedUpdate(this, onCompleteUpdate)
    is AppUpdateResult.InProgress -> InAppUpdateState.InProgressUpdate(this)
}

internal fun shouldUpdateImmediately(updateInfo: AppUpdateInfo, settings: InAppUpdateSettings): Boolean =
    with(updateInfo) {
        updateInfo.updateAvailability() ==
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ||
                (isImmediateUpdateAllowed && updatePriority() >= settings.highPrioritizeUpdates)
    }


internal fun shouldPromptToUpdate(
    declined: Declined,
    updateInfo: AppUpdateInfo,
    settings: InAppUpdateSettings,
): Boolean {
    with(updateInfo) {
        // No point in prompting if we're not allowed to update.
        if (!isFlexibleUpdateAllowed) return false

        val promptIntervalDays = when {
            // For medium-priority updates, prompt once per day.
            updatePriority() >= settings.mediumPrioritizeUpdates -> settings.promptIntervalMediumPrioritizeUpdateInDays
            // For low-priority updates, prompt once per week.
            else -> settings.promptIntervalLowPrioritizeUpdateInDays
        }

        // To prompt for an optional update, the update must be at least
        // `promptIntervalDays` old and the user declined this update at
        // least `promptIntervalDays` ago (or has never declined).
        if ((clientVersionStalenessDays() ?: 0) < promptIntervalDays) return false

        if (declined.version == availableVersionCode() &&
            declined.date.daysUntil(
                Clock.System.now(),
                TimeZone.currentSystemDefault()
            ) < promptIntervalDays
        ) {
            return false
        }

        return true
    }
}