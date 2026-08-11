package com.mcandle.uwbcontrolee.uwb

import androidx.core.uwb.RangingParameters
import java.util.UUID

/**
 * UWB 세션 계약 기본값 — 보드(DWM3001CDK, UCI 펌웨어) 쪽과 바이트 단위로 일치해야 한다.
 *
 * 기준값 출처 (4단계에서 쌍으로 대조 완료, 2026-07-04):
 * https://github.com/sasodoma/uwb-ranging @ aad72a0 (2025-05-28)
 *   - 앱 쪽: android_app/.../UWBRanging.kt
 *   - PC 쪽: new_python_script/run_fira_twr.py (기본값이 이미 Android 프로필에 맞게 수정된 사본)
 * 상세 대조표: docs/파라미터_대조_4단계.md
 *
 * PC 스크립트는 반드시 sasodoma 리포의 사본을 기본 옵션으로 실행할 것:
 *   python run_fira_twr.py -p <COMx> --mac 00:00 --dest-mac <내 주소>
 */
object UwbDefaults {
    /** FiRa Session ID (UI에서 변경 가능). PC 쪽 -s 기본값도 42 */
    const val SESSION_ID: Int = 42

    /** unicast이므로 sub-session 미사용 (PC 쪽도 미설정) */
    const val SUB_SESSION_ID: Int = 0

    /** UWB 채널 번호 (PC: CHANNEL_NUMBER=9) */
    const val CHANNEL: Int = 9

    /** 프리앰블 인덱스 (PC: PREAMBLE_CODE_INDEX=9) */
    const val PREAMBLE_INDEX: Int = 9

    /**
     * Static STS 8바이트 = Vendor ID 2B + STS IV 6B.
     * PC 쪽 UCI 파라미터는 리틀엔디언 정수라서 바이트 나열이 뒤집힌다:
     *   VENDOR_ID     0x0708         → 08 07
     *   STATIC_STS_IV 0x060504030201 → 01 02 03 04 05 06
     * (sasodoma 앱의 sessionKeyInfo와 바이트 단위 동일)
     */
    val STATIC_STS_KEY: ByteArray =
        byteArrayOf(0x08, 0x07, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

    /**
     * 갱신 주기: FREQUENT = 레인징 주기 120ms.
     * PC 쪽 RANGING_DURATION 기본값 120과 쌍 — AUTOMATIC(240ms)을 쓰면 불일치로 무증상 실패.
     */
    const val UPDATE_RATE_TYPE: Int = RangingParameters.RANGING_UPDATE_RATE_FREQUENT

    /** 보드 short MAC 기본값. PC 스크립트를 --mac 00:00으로 실행하는 것과 쌍 */
    const val DEFAULT_BOARD_MAC: String = "00:00"

    /** 화면 고정 파라미터 표기용 한 줄 요약 (FR-4) */
    const val CONFIG_SUMMARY: String =
        "CH 9 · PRE 9 · DS-TWR unicast · 120ms · STS 08 07 01 02 03 04 05 06"

    /** 이벤트 로그 최대 줄 수 (NFR-5) */
    const val MAX_LOG_LINES: Int = 500

    // ── OOB (BLE) 계약 — docs/oob/BLE_OOB_인터페이스_사양서.md §3~4 사본.
    //    불일치 시 사양서가 유일 기준 (CLAUDE.md "OOB 계약" 참고) ──────────

    /** GATT Service UUID (콘솔이 이 UUID로 필터 스캔) */
    val OOB_SERVICE_UUID: UUID = UUID.fromString("5F1D0001-9A8B-4C7D-B2E3-6F4A5D8C9B0A")

    /** OOB_INFO Characteristic UUID — Read + Notify (Write 없음) */
    val OOB_CHARACTERISTIC_UUID: UUID = UUID.fromString("5F1D0002-9A8B-4C7D-B2E3-6F4A5D8C9B0A")

    /** 광고 Local Name — 콘솔이 UUID 필터와 함께 참고 (사양서 §3) */
    const val OOB_LOCAL_NAME: String = "UWB-OOB"

    /** 페이로드 protocol_version (오프셋 0, 1B). v1 고정 */
    const val OOB_PROTOCOL_VERSION: Byte = 0x01

    /** OOB_INFO 페이로드 고정 길이 (사양서 §4) */
    const val OOB_PAYLOAD_SIZE: Int = 7

    /**
     * OOB_INFO 페이로드(7B) 조립 (사양서 §4, 바이트 순서 최대 함정 주의):
     *   offset 0   1B  protocol_version = 0x01
     *   offset 1-2 2B  uwb_address — 화면 표시 순서 그대로, 반전 없음
     *   offset 3-6 4B  session_id  — uint32 little-endian (42 → 2A 00 00 00)
     */
    fun buildOobPayload(uwbAddress: ByteArray, sessionId: Int): ByteArray {
        require(uwbAddress.size == 2) {
            "uwbAddress must be 2 bytes, got ${uwbAddress.size}"
        }
        return byteArrayOf(
            OOB_PROTOCOL_VERSION,
            uwbAddress[0],
            uwbAddress[1],
            (sessionId and 0xFF).toByte(),
            ((sessionId ushr 8) and 0xFF).toByte(),
            ((sessionId ushr 16) and 0xFF).toByte(),
            ((sessionId ushr 24) and 0xFF).toByte(),
        )
    }
}
