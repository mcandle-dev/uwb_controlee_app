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
import com.mcandle.uwbcontrolee.uwb.OobMode
import com.mcandle.uwbcontrolee.uwb.bleOobPermissionsFor
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
     * BLE OOB 권한 동시 요청 (FR-14) — 현재 모드에 필요한 BLE 권한(모드 3 은 SCAN 포함,
     * spec 001 D5)이 모두 허용일 때만 granted. 결과 맵 대신 결과 시점의 실제 보유 상태로
     * 판정한다 (이미 허용돼 요청에서 빠진 권한 포함). 알림 권한은 OOB 판정에 미반영.
     */
    private val blePermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            viewModel.onBlePermissionResult(
                hasBleOobPermissions(this, viewModel.uiState.value.oobMode),
            )
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
                    onToggleConsoleSim = ::toggleConsoleSimulator,
                    onToggleAutoWatch = ::toggleAutoWatch,
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
        // 모드는 startRanging 전에 읽는다 — 세션 중 모드 변경은 어차피 금지 (규칙 0)
        val mode: OobMode = viewModel.uiState.value.oobMode
        viewModel.startRanging()
        val sessionStarted: Boolean = viewModel.uiState.value.isSessionActive
        if (!sessionStarted) return
        // 권한 다이얼로그를 연달아 띄우면 앞선 요청이 취소되므로 한 번에 요청한다.
        if (!hasBleOobPermissions(this, mode)) {
            blePermissionLauncher.launch(bleOobPermissionsFor(mode) + missingNotificationPermission())
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
        blePermissionLauncher.launch(bleOobPermissionsFor(viewModel.uiState.value.oobMode))
    }

    /**
     * 자동 감시 토글 (spec 002 T301) — 켤 때 모드 4 권한(SCAN·CONNECT)과 알림 권한
     * (웨이크 알림 폴백용)이 없으면 먼저 요청한다 (허용 후 재탭 — 시뮬 버튼과 동일 패턴).
     */
    private fun toggleAutoWatch() {
        val enabling: Boolean = !viewModel.uiState.value.autoWatchEnabled
        if (enabling && !hasBleOobPermissions(this, OobMode.CENTRAL)) {
            blePermissionLauncher.launch(
                bleOobPermissionsFor(OobMode.CENTRAL) + missingNotificationPermission(),
            )
            return
        }
        viewModel.toggleAutoWatch()
    }

    /**
     * 콘솔 광고 시뮬레이터 (검수 12 테스트 보조) — 광고 권한이 없으면 먼저 요청한다
     * (허용 후 버튼을 다시 누르면 송출 시작 — Start 와 달리 세션이 없으므로 단순 재탭으로 충분).
     */
    private fun toggleConsoleSimulator() {
        if (!hasBleOobPermissions(this, OobMode.BEACON)) {
            blePermissionLauncher.launch(bleOobPermissionsFor(OobMode.BEACON))
            return
        }
        viewModel.toggleConsoleSimulator()
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
