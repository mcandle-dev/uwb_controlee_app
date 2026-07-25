package com.mcandle.uwbcontrolee

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

    /**
     * BLE OOB 권한 2종 동시 요청 (FR-14) — BLE 2종이 모두 허용일 때만 granted.
     * 알림 권한이 같은 요청에 섞여 있어도 OOB 판정에는 반영하지 않는다.
     */
    private val blePermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            viewModel.onBlePermissionResult(BLE_OOB_PERMISSIONS.all { results[it] == true })
        }

    /** FGS 알림 표시용 (NFR-3) — 거부돼도 FGS·세션 동작에는 영향 없음, 알림만 숨겨짐 */
    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
     * Start = OOB 광고 시작, OOB_INFO Read 직후 UWB 시작.
     * BLE 권한이 거부되거나 OOB가 시간 초과하면 수동 UWB 흐름으로 폴백한다.
     * 세션 중에는 FGS가 떠 있으므로 백그라운드에서도 광고·레인징이 유지된다 (NFR-3).
     */
    private fun startRangingWithOob() {
        viewModel.startRanging()
        val sessionStarted: Boolean = viewModel.uiState.value.isSessionActive
        if (!sessionStarted) return
        // 권한 다이얼로그를 연달아 띄우면 앞선 요청이 취소되므로 한 번에 요청한다.
        if (!hasBleOobPermissions(this)) {
            blePermissionLauncher.launch(BLE_OOB_PERMISSIONS + missingNotificationPermission())
        } else {
            missingNotificationPermission().firstOrNull()
                ?.let(notificationPermissionLauncher::launch)
        }
    }

    /** API 33+에서 아직 없는 POST_NOTIFICATIONS — 항상 0개 또는 1개 (NFR-3) */
    private fun missingNotificationPermission(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return emptyArray()
        val granted: Boolean = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) emptyArray() else arrayOf(Manifest.permission.POST_NOTIFICATIONS)
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
