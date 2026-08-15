package com.mcandle.uwbcontrolee.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mcandle.uwbcontrolee.MainViewModel
import com.mcandle.uwbcontrolee.UiState
import com.mcandle.uwbcontrolee.uwb.OobMode
import com.mcandle.uwbcontrolee.uwb.OobStatus
import com.mcandle.uwbcontrolee.uwb.RangingState
import com.mcandle.uwbcontrolee.uwb.UwbAvailability
import com.mcandle.uwbcontrolee.uwb.UwbDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BannerColorError: Color = Color(0xFFB3261E)
private val BannerColorWarning: Color = Color(0xFFE07800)
private val BadgeColorIdle: Color = Color(0xFF757575)
private val BadgeColorWaiting: Color = Color(0xFF1565C0)
private val BadgeColorRanging: Color = Color(0xFF2E7D32)
private val BadgeColorNoSignal: Color = Color(0xFFF9A825)
private val BadgeColorProblem: Color = Color(0xFFB3261E)
private val DistanceWarnColor: Color = Color(0xFFE07800)
private val LogBackgroundColor: Color = Color(0xFF1E1E1E)
private val LogTextColor: Color = Color(0xFFB9E0A5)
private val LogConsoleHeight = 180.dp
private val ScreenPadding = 12.dp
private val SectionSpacing = 8.dp

/** 거리 경고 범위 (radar_test_console과 동일 규칙, FR-6) */
private const val DistanceWarnMaxCm: Int = 5000
private const val TickerIntervalMs: Long = 1_000L
private const val MillisPerSecond: Double = 1_000.0

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit,
    onOpenUwbSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartRanging: () -> Unit,
    onRequestBlePermissions: () -> Unit,
    onToggleConsoleSim: () -> Unit,
    onToggleAutoWatch: () -> Unit,
) {
    val uiState: UiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
    val clipboard: Clipboard = LocalClipboard.current
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            AvailabilityBanner(
                availability = uiState.availability,
                onRequestPermission = onRequestPermission,
                onOpenUwbSettings = onOpenUwbSettings,
                onOpenAppSettings = onOpenAppSettings,
            )
            BleOobBanner(
                visible = uiState.blePermissionDenied,
                onRequestBlePermissions = onRequestBlePermissions,
                onOpenAppSettings = onOpenAppSettings,
            )
            MyAddressCard(
                myAddress = uiState.myAddress,
                oobStatus = uiState.oobStatus,
                oobMode = uiState.oobMode,
                onCopy = { address ->
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("UWB address", address)),
                        )
                        viewModel.onAddressCopied(address)
                        snackbarHostState.showSnackbar(message = "복사됨: $address")
                    }
                },
            )
            SessionInputs(
                uiState = uiState,
                onBoardMacChanged = viewModel::onBoardMacChanged,
                onSessionIdChanged = viewModel::onSessionIdChanged,
                onOobModeChanged = viewModel::onOobModeChanged,
                onToggleConsoleSim = onToggleConsoleSim,
            )
            ControlSection(
                uiState = uiState,
                onStart = onStartRanging,
                onStop = viewModel::stopRanging,
                onToggleAutoWatch = onToggleAutoWatch,
            )
            MeasurementPanel(uiState = uiState, modifier = Modifier.weight(1f))
            LogConsole(logLines = uiState.logLines)
        }
    }
}

/** (A) 상태 배너 — 문제가 있을 때만 노출 (FR-1/FR-2) */
@Composable
private fun AvailabilityBanner(
    availability: UwbAvailability,
    onRequestPermission: () -> Unit,
    onOpenUwbSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    when (availability) {
        UwbAvailability.READY -> Unit
        UwbAvailability.CHECKING ->
            BannerCard(color = BannerColorWarning, message = "UWB 상태 확인 중…")
        UwbAvailability.NOT_SUPPORTED ->
            BannerCard(
                color = BannerColorError,
                message = "이 기기는 UWB를 지원하지 않습니다 (앱 기능 사용 불가)",
            )
        UwbAvailability.PERMISSION_DENIED ->
            BannerCard(color = BannerColorError, message = "UWB 권한이 필요합니다") {
                Button(onClick = onRequestPermission) { Text(text = "권한 요청") }
                Button(onClick = onOpenAppSettings) { Text(text = "앱 설정") }
            }
        UwbAvailability.DISABLED ->
            BannerCard(
                color = BannerColorWarning,
                message = "설정 → 연결에서 UWB(초광대역)를 켜세요",
            ) {
                Button(onClick = onOpenUwbSettings) { Text(text = "설정 열기") }
            }
    }
}

/** BLE 권한 거부 안내 배너 (FR-14) — OOB만 비활성, UWB 수동 흐름은 계속 가능함을 명시 */
@Composable
private fun BleOobBanner(
    visible: Boolean,
    onRequestBlePermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    if (!visible) return
    BannerCard(
        color = BannerColorWarning,
        message = "BLE 권한 거부 — 주소 자동 전달(OOB)만 비활성. 수동 입력은 계속 가능",
    ) {
        Button(onClick = onRequestBlePermissions) { Text(text = "권한 재요청") }
        Button(onClick = onOpenAppSettings) { Text(text = "앱 설정") }
    }
}

@Composable
private fun BannerCard(
    color: Color,
    message: String,
    actions: (@Composable () -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            Text(text = message, color = Color.White, fontWeight = FontWeight.Bold)
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(SectionSpacing)) { actions() }
            }
        }
    }
}

/** (B) 내 주소 카드 — 모노스페이스 큰 글씨, 탭=복사. 옆에 OOB 배지 (FR-3/FR-16) */
@Composable
private fun MyAddressCard(
    myAddress: String?,
    oobStatus: OobStatus,
    oobMode: OobMode,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = myAddress != null) { myAddress?.let(onCopy) },
    ) {
        Column(modifier = Modifier.padding(ScreenPadding)) {
            Text(
                text = "내 UWB 주소 (탭=복사 → PC --dest-mac)",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SectionSpacing),
            ) {
                Text(
                    text = myAddress ?: "--:--",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                )
                OobBadge(status = oobStatus, mode = oobMode)
            }
        }
    }
}

/**
 * OOB 상태 소형 배지 (FR-16) — OFF면 아무것도 그리지 않음. 모드 3(SCANNER)은 같은 상태값의
 * 의미가 다르다 (§6-1 매핑: ADVERTISING=스캔중, CONNECTED=광고 수신 확정) — 표기만 분기.
 */
@Composable
private fun OobBadge(status: OobStatus, mode: OobMode) {
    // 관찰형 모드(3 SCANNER · 4 CENTRAL)는 ADVERTISING 상태값의 의미가 "스캔중"이다 (§6-1 매핑)
    val isObserver: Boolean = mode == OobMode.SCANNER || mode == OobMode.CENTRAL
    val badge: Pair<Color, String> = when (status) {
        OobStatus.OFF -> return
        OobStatus.ADVERTISING ->
            BadgeColorWaiting to if (isObserver) "⚪ 스캔중" else "⚪ 광고중"
        OobStatus.CONNECTED ->
            // 모드 3 은 연결 없는 수신 확정, 모드 4 는 진짜 GATT 연결 — 표기 구분
            BadgeColorRanging to if (mode == OobMode.SCANNER) "🔵 광고 수신됨" else "🔵 콘솔 연결됨"
        OobStatus.UNAVAILABLE -> BadgeColorIdle to "OOB 비활성"
    }
    Box(
        modifier = Modifier
            .background(color = badge.first, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = badge.second,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 3모드 전체 선택 가능 (SCANNER 는 T301 구현 후 2026-08-12 노출) */
private val SelectableOobModes: List<OobMode> = OobMode.entries.toList()

/** (C) 설정 입력 — 보드 MAC + Session ID + OOB 모드, 레인징 중 비활성. 고정 파라미터 표기 (FR-4) */
@Composable
private fun SessionInputs(
    uiState: UiState,
    onBoardMacChanged: (String) -> Unit,
    onSessionIdChanged: (String) -> Unit,
    onOobModeChanged: (OobMode) -> Unit,
    onToggleConsoleSim: () -> Unit,
) {
    val editable: Boolean = !uiState.isSessionActive
    Column(verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SectionSpacing)) {
            OutlinedTextField(
                value = uiState.boardMacInput,
                onValueChange = onBoardMacChanged,
                modifier = Modifier.weight(1f),
                enabled = editable,
                label = { Text(text = "보드 MAC (hex 2B)") },
                isError = uiState.boardMacError != null,
                supportingText = { uiState.boardMacError?.let { Text(text = it) } },
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.sessionIdInput,
                onValueChange = onSessionIdChanged,
                modifier = Modifier.weight(1f),
                enabled = editable,
                label = { Text(text = "Session ID") },
                isError = uiState.sessionIdError != null,
                supportingText = { uiState.sessionIdError?.let { Text(text = it) } },
                singleLine = true,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SectionSpacing),
        ) {
            OobModeSelector(
                selected = uiState.oobMode,
                enabled = editable, // 레인징 중 변경 금지 (사양서 규칙 0)
                onModeSelected = onOobModeChanged,
            )
            Text(
                text = UwbDefaults.CONFIG_SUMMARY,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            // 검수 12 테스트 보조 — 이 폰을 "가짜 콘솔"(5F1D0003 송출)로. 상대 폰은 모드 3
            TextButton(onClick = onToggleConsoleSim) {
                Text(text = if (uiState.consoleSimActive) "시뮬 중지" else "콘솔시뮬")
            }
        }
    }
}

/** OOB 모드 드롭다운 (spec 001, plan D3) — 콘솔과 짝 맞추는 화면에서 한눈에 보이게 */
@Composable
private fun OobModeSelector(
    selected: OobMode,
    enabled: Boolean,
    onModeSelected: (OobMode) -> Unit,
) {
    var expanded: Boolean by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(text = "OOB ${selected.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SelectableOobModes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(text = mode.label) },
                    onClick = {
                        expanded = false
                        onModeSelected(mode)
                    },
                )
            }
        }
    }
}

/** (D) Start/Stop + 절차 안내 (FR-5/FR-10) + 자동 감시 토글 (spec 002 T301, 모드 4 전용) */
@Composable
private fun ControlSection(
    uiState: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleAutoWatch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SectionSpacing)) {
            Button(
                onClick = onStart,
                enabled = uiState.canStart,
                modifier = Modifier.weight(1f),
            ) { Text(text = "▶ Start") }
            Button(
                onClick = onStop,
                enabled = uiState.canStop,
                modifier = Modifier.weight(1f),
            ) { Text(text = "■ Stop") }
        }
        Text(
            text = "① 앱 Start → ② PC에서 run_fira_twr.py --dest-mac <내 주소> 실행 (순서 중요)",
            style = MaterialTheme.typography.bodySmall,
        )
        if (uiState.oobMode == OobMode.CENTRAL) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SectionSpacing),
            ) {
                Switch(
                    checked = uiState.autoWatchEnabled,
                    onCheckedChange = { onToggleAutoWatch() },
                )
                Text(
                    text = "자동 감시 — 앱을 닫아도 콘솔 발견 시 자동 시작 " +
                        "(배터리 최적화 '제한 없음' 필수 · 재부팅 시 재설정)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** (E) 측정 표시 — 상태 배지 + 거리/각도 큰 숫자 + 마지막 갱신 (FR-6/FR-7) */
@Composable
private fun MeasurementPanel(uiState: UiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StatusBadge(rangingState = uiState.rangingState, noSignal = uiState.noSignal)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            MeasurementValue(
                label = "거리",
                value = uiState.distanceCm?.toString() ?: "---",
                unit = "cm",
                valueColor = distanceColor(uiState.distanceCm),
            )
            MeasurementValue(
                label = "각도",
                value = uiState.azimuthDeg?.toString() ?: "N/A",
                unit = "°",
                valueColor = MaterialTheme.colorScheme.onSurface,
            )
        }
        LastUpdateText(lastMeasurementAtMillis = uiState.lastMeasurementAtMillis)
    }
}

@Composable
private fun distanceColor(distanceCm: Int?): Color {
    if (distanceCm == null) return MaterialTheme.colorScheme.onSurface
    return if (distanceCm in 0..DistanceWarnMaxCm) {
        MaterialTheme.colorScheme.onSurface
    } else {
        DistanceWarnColor
    }
}

@Composable
private fun MeasurementValue(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(text = unit, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 상태 배지 — IDLE 회색/WAITING 파랑/RANGING 초록/수신없음 노랑/문제 빨강 (FR-7/8) */
@Composable
private fun StatusBadge(rangingState: RangingState, noSignal: Boolean) {
    val badge: Pair<Color, String> = when {
        noSignal -> BadgeColorNoSignal to "RANGING · 수신없음"
        rangingState == RangingState.IDLE -> BadgeColorIdle to "IDLE"
        rangingState == RangingState.WAITING -> BadgeColorWaiting to "WAITING"
        rangingState == RangingState.RANGING -> BadgeColorRanging to "RANGING"
        rangingState == RangingState.DISCONNECTED -> BadgeColorProblem to "DISCONNECTED"
        else -> BadgeColorProblem to "ERROR"
    }
    Box(
        modifier = Modifier
            .background(color = badge.first, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text = "● ${badge.second}", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** "마지막 갱신: X.Xs 전" — 1초마다 갱신 (FR-6) */
@Composable
private fun LastUpdateText(lastMeasurementAtMillis: Long?) {
    var nowMillis: Long by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastMeasurementAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(TickerIntervalMs)
        }
    }
    val text: String = if (lastMeasurementAtMillis == null) {
        "마지막 갱신: --"
    } else {
        val agoSeconds: Double = (nowMillis - lastMeasurementAtMillis) / MillisPerSecond
        "마지막 갱신: ${"%.1f".format(agoSeconds)}s 전"
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

/** (F) 로그 콘솔 — 시간순, 새 항목 시 자동 스크롤 (FR-9) */
@Composable
private fun LogConsole(logLines: List<String>) {
    val listState: LazyListState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(LogConsoleHeight)
            .background(LogBackgroundColor)
            .padding(SectionSpacing),
    ) {
        items(items = logLines) { line ->
            Text(
                text = line,
                color = LogTextColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}
