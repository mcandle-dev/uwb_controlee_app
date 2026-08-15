package com.mcandle.uwbcontrolee.uwb

/**
 * BLE OOB 교환 모드 (사양서 v0.3 §2 — spec 001).
 *
 * 콘솔과 폰의 모드는 짝으로만 성립한다 — 짝이 어긋나면 오류가 아니라 "발견 못함"으로
 * 끝난다 (사양서 §7-11). UWB 역할은 모든 모드에서 불변: 보드=controller, 폰=controlee (P15).
 * 바뀌는 것은 BLE 교환 방향뿐이다.
 */
enum class OobMode(val storageValue: String, val label: String) {
    /** 모드 1 (v1 현행, 기본값) — GATT peripheral 광고→연결→Read/Notify. 콘솔 짝: SCANNER */
    ADVERTISE_GATT("ADVERTISE_GATT", "1 · GATT 광고 (v1)"),

    /** 모드 2 — OOB_INFO 7B를 Service Data(5F1D0001)로 송출, 연결 없음. 콘솔 짝: BEACON */
    BEACON("BEACON", "2 · BEACON 송출"),

    /** 모드 3 — 콘솔 광고(5F1D0003) 관찰, 보드 MAC·SID 자동 반영. 콘솔 짝: ADVERTISE */
    SCANNER("SCANNER", "3 · SCANNER 관찰"),

    /**
     * 모드 4 (v0.5, spec 002) — 폰이 central: 콘솔 connectable 광고를 스캔·연결해
     * BOARD_INFO Read / PHONE_INFO Write. **폰 송출 0건 — iOS 가 성립하는 유일한 자동
     * 경로** (사양서 §10). 콘솔 짝: GATT-SERVER
     */
    CENTRAL("CENTRAL", "4 · GATT 연결 (iOS)"),
    ;

    companion object {
        /** 기본값 = 검증된 v1 경로 (사양서 §2 — 회귀 기준으로 보존) */
        val DEFAULT: OobMode = ADVERTISE_GATT

        /** 저장값 → 모드. null·미지 값은 기본값 (구버전 prefs·오염값 하위 호환) */
        fun fromStorageValue(value: String?): OobMode =
            entries.firstOrNull { mode -> mode.storageValue == value } ?: DEFAULT
    }
}
