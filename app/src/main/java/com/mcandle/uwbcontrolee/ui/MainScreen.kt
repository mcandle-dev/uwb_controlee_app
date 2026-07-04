package com.mcandle.uwbcontrolee.ui

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mcandle.uwbcontrolee.uwb.UwbAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val BannerColorError: Color = Color(0xFFB3261E)
private val BannerColorWarning: Color = Color(0xFFE07800)
private val ReadyColor: Color = Color(0xFF2E7D32)
private val LogBackgroundColor: Color = Color(0xFF1E1E1E)
private val LogTextColor: Color = Color(0xFFB9E0A5)
private val LogConsoleHeight = 200.dp
private val ScreenPadding = 12.dp

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit,
    onOpenUwbSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(ScreenPadding),
        ) {
            Text(text = "UWB Controlee 테스트", style = MaterialTheme.typography.titleLarge)
            AvailabilityBanner(
                availability = uiState.availability,
                onRequestPermission = onRequestPermission,
                onOpenUwbSettings = onOpenUwbSettings,
                onOpenAppSettings = onOpenAppSettings,
            )
            MyAddressCard(
                myAddress = uiState.myAddress,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "다음 단계에서 추가: 레인징 제어·측정(3단계)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LogConsole(logLines = uiState.logLines)
        }
    }
}

/** (B) 내 주소 카드 — 모노스페이스 큰 글씨, 탭=복사. 미발급이면 "--:--" (FR-3) */
@Composable
private fun MyAddressCard(
    myAddress: String?,
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
            Text(
                text = myAddress ?: "--:--",
                fontFamily = FontFamily.Monospace,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** (A) 상태 배너 — 문제가 없으면(READY) 초록 한 줄, 문제 상태별 안내+액션 (FR-1/FR-2) */
@Composable
private fun AvailabilityBanner(
    availability: UwbAvailability,
    onRequestPermission: () -> Unit,
    onOpenUwbSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    when (availability) {
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
        UwbAvailability.READY ->
            BannerCard(color = ReadyColor, message = "UWB 사용 가능")
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
    }
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
            .padding(8.dp),
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
