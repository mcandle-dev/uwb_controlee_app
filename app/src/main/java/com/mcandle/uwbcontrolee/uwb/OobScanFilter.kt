package com.mcandle.uwbcontrolee.uwb

/**
 * 모드 3(SCANNER) 스캔 결과 수용 판정 — 순수 함수 (P3, plan 001 D6, 사양서 §7-12/13).
 *
 * Android 스캔 캐시는 같은 콘솔의 광고를 합쳐 보고할 수 있어 죽은(캐시 잔존) 광고를
 * rssi/timestamp 로 거르고, 직전 반영값과 같은 payload 는 재반영하지 않는다
 * (입력칸 깜빡임 방지). BLE API 를 만지지 않으므로 JVM 테스트로 검증한다.
 */
object OobScanFilter {

    /** 이보다 오래된 광고는 캐시 잔존물로 보고 버린다 (사양서 §7-12) */
    const val MAX_ADVERT_AGE_MS: Long = 5_000L

    /**
     * 죽은 광고 제거용 관대한 하한 — 페어 테스트는 근거리 전제라 이보다 약한 신호는
     * 잔존 캐시 의심. 정상 광고를 거르지 않도록 일부러 낮게 잡았다 (임계 근거: 없음/보수적).
     */
    const val MIN_RSSI_DBM: Int = -95

    /** 판정 결과 — APPLY 외에는 모두 "반영하지 않음" (사유는 로그용) */
    enum class Verdict { APPLY, MALFORMED, STALE, WEAK, DUPLICATE }

    /**
     * @param serviceData 수신한 Service Data (파서 규칙: 길이 ≥7B 만 검사, 추가 바이트 무시 — 사양서 §4)
     * @param advertAgeMs 광고 수신 시각으로부터 경과 시간 (ScanResult.timestampNanos 기준 환산)
     * @param lastAppliedPayload 직전에 입력칸에 반영한 payload (아직 없으면 null)
     */
    fun evaluate(
        serviceData: ByteArray?,
        rssiDbm: Int,
        advertAgeMs: Long,
        lastAppliedPayload: ByteArray?,
    ): Verdict = when {
        serviceData == null || serviceData.size < UwbDefaults.OOB_PAYLOAD_SIZE ->
            Verdict.MALFORMED
        advertAgeMs > MAX_ADVERT_AGE_MS -> Verdict.STALE
        rssiDbm < MIN_RSSI_DBM -> Verdict.WEAK
        lastAppliedPayload != null && serviceData.contentEquals(lastAppliedPayload) ->
            Verdict.DUPLICATE
        else -> Verdict.APPLY
    }
}
