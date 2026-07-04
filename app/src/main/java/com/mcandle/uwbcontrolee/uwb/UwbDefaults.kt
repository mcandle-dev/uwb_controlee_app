package com.mcandle.uwbcontrolee.uwb

/**
 * UWB 세션 계약 기본값 — 보드(DWM3001CDK, UCI 펌웨어) 쪽과 바이트 단위로 일치해야 한다.
 *
 * 주의: 아래 값은 CLAUDE.md의 placeholder 기본값이다. 실제 기준값은
 * https://github.com/sasodoma/uwb-ranging 의 run_fira_twr.py + Android 앱 쌍에서
 * 추출해 4단계에서 대조·정렬한다. 파라미터가 하나라도 어긋나면 에러 없이
 * 조용히 아무것도 안 나온다.
 */
object UwbDefaults {
    /** FiRa Session ID (UI에서 변경 가능) */
    const val SESSION_ID: Int = 42

    /** unicast이므로 sub-session 미사용 */
    const val SUB_SESSION_ID: Int = 0

    /** UWB 채널 번호 */
    const val CHANNEL: Int = 9

    /** 프리앰블 인덱스 */
    const val PREAMBLE_INDEX: Int = 9

    /** Static STS: Vendor ID 2B + STS IV 6B = 8바이트 */
    val STATIC_STS_KEY: ByteArray =
        byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01)

    /** 보드 short MAC 기본값 (hex 2바이트, UI 입력) */
    const val DEFAULT_BOARD_MAC: String = "00:01"

    /** 화면 고정 파라미터 표기용 한 줄 요약 (FR-4) */
    const val CONFIG_SUMMARY: String = "CH 9 · PRE 9 · DS-TWR unicast · STS 08 07 06 05 04 03 02 01"

    /** 이벤트 로그 최대 줄 수 (NFR-5) */
    const val MAX_LOG_LINES: Int = 500
}
