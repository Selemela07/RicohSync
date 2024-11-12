package dev.sebastiano.ricohsync.devicesync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.DeadObjectException
import android.os.IBinder
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juul.kable.Advertisement
import com.juul.kable.Peripheral
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class DeviceSyncViewModel(
    private val advertisement: Advertisement,
    bindingContextProvider: () -> Context,
) : ViewModel() {
    private val _state = mutableStateOf<DeviceSyncState>(DeviceSyncState.Starting)
    val state: State<DeviceSyncState> = _state

    private val serviceBinder = MutableStateFlow<DeviceSyncService.DeviceSyncServiceBinder?>(null)
    private var collectJob: Job? = null

    init {
        startAndBindService(bindingContextProvider)
        serviceBinder
            .onEach { binder -> if (binder != null) onBound(binder) else onUnbound() }
            .flowOn(Dispatchers.Default)
            .launchIn(viewModelScope)
    }

    private fun startAndBindService(bindingContextProvider: () -> Context) {
        viewModelScope.launch(Dispatchers.Default) {
            val context = bindingContextProvider()
            val intent = Intent(context, DeviceSyncService::class.java)
            context.startService(intent)

            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        serviceBinder.value = service as DeviceSyncService.DeviceSyncServiceBinder
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        serviceBinder.value = null
                    }
                }
            context.bindService(intent, connection, 0 /* No flags */)
        }
    }

    private fun onBound(binder: DeviceSyncService.DeviceSyncServiceBinder) {
        collectJob =
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val service = DeviceSyncService.getInstanceFrom(binder)
                    service.connectAndSync(advertisement)
                    service.state.collect { _state.value = it }
                } catch (e: DeadObjectException) {
                    onUnbound()
                }
            }
    }

    private fun onUnbound() {
        collectJob?.cancel("Service connection died")
        collectJob = null
    }
}

internal sealed interface DeviceSyncState {

    data object Starting : DeviceSyncState

    data class Connecting(val advertisement: Advertisement) : DeviceSyncState

    data class Syncing(
        val peripheral: Peripheral,
        val lastSyncTime: ZonedDateTime?,
        val lastLocation: Location?,
    ) : DeviceSyncState
}
