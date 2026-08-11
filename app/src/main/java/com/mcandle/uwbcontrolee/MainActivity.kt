package com.mcandle.uwbcontrolee

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.mcandle.uwbcontrolee.ui.MainScreen
import com.mcandle.uwbcontrolee.uwb.BLE_OOB_PERMISSIONS
import com.mcandle.uwbcontrolee.uwb.hasBleOobPermissions

/** 숨은 설정 액션 — 일부 기기에서 UWB 설정 화면 직행. 없으면 연결 설정으로 폴백 */
private const val ACTION_UWB_SETTINGS: String = "android.settings.UWB_SETTINGS"

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onPermissionResult(granted)
        }

    /** BLE OOB 권한 2종 동시 요청 (FR-14) — 결과는 모두 허용일 때만 granted */
    private val blePermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            viewModel.onBlePermissionResult(results.values.all { it })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermission = ::requestRangingPermission,
                    onOpenUwbSettings = ::openUwbSettings,
                    onOpenAppSettings = ::openAppSettings,
                    onStartRanging = ::startRangingWithOob,
                    onRequestBlePermissions = ::requestBlePermissions,
                )
            }
        }
    }

    /** 설정에서 토글을 바꾸고 돌아오는 경우가 흔하므로 매 복귀마다 재판정 */
    override fun onResume() {
        super.onResume()
        viewModel.refreshAvailability()
    }

    private fun requestRangingPermission() {
        permissionLauncher.launch(Manifest.permission.UWB_RANGING)
    }

    /**
     * Start = UWB 즉시 시작 + BLE 권한 없으면 그때 요청 (FR-14 "첫 진입 시").
     * 허용 응답이 오면 ViewModel이 뒤늦게 OOB를 연다 — BLE가 UWB를 절대 막지 않음.
     */
    private fun startRangingWithOob() {
        viewModel.startRanging()
        val sessionStarted: Boolean = viewModel.uiState.value.isSessionActive
        if (sessionStarted && !hasBleOobPermissions(this)) {
            blePermissionLauncher.launch(BLE_OOB_PERMISSIONS)
        }
    }

    private fun requestBlePermissions() {
        blePermissionLauncher.launch(BLE_OOB_PERMISSIONS)
    }

    private fun openUwbSettings() {
        try {
            startActivity(Intent(ACTION_UWB_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun openAppSettings() {
        val intent: Intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }
}
