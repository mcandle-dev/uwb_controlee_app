package com.mcandle.uwbcontrolee.uwb

import androidx.core.uwb.RangingParameters

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
}
