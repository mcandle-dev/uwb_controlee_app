package com.mcandle.uwbcontrolee.uwb

import com.mcandle.uwbcontrolee.uwb.OobScanFilter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 모드 3 스캔 캐시 필터 검증 (spec 001 T103 — plan D6, 사양서 §7-12/13).
 * 신선한 광고만 반영하고, 죽은 광고·중복 payload 는 사유별 Verdict 로 걸러지는지 확인.
 */
class OobScanFilterTest {

    /** 사양서 §4 예시 payload — 보드 5F:DD, session 42 */
    private val payload: ByteArray =
        byteArrayOf(0x01, 0x5F, 0xDD.toByte(), 0x2A, 0x00, 0x00, 0x00)

    private fun evaluate(
        serviceData: ByteArray? = payload,
        rssiDbm: Int = -60,
        advertAgeMs: Long = 100L,
        lastAppliedPayload: ByteArray? = null,
    ): Verdict = OobScanFilter.evaluate(serviceData, rssiDbm, advertAgeMs, lastAppliedPayload)

    @Test
    fun `fresh strong first advert is applied`() {
        assertEquals(Verdict.APPLY, evaluate())
    }

    @Test
    fun `null service data is malformed`() {
        assertEquals(Verdict.MALFORMED, evaluate(serviceData = null))
    }

    @Test
    fun `service data shorter than 7 bytes is malformed`() {
        assertEquals(Verdict.MALFORMED, evaluate(serviceData = byteArrayOf(0x01, 0x5F)))
    }

    @Test
    fun `extra trailing bytes are still accepted (forward compat - spec section 4)`() {
        assertEquals(Verdict.APPLY, evaluate(serviceData = payload + byteArrayOf(0x7F)))
    }

    @Test
    fun `advert older than max age is stale`() {
        assertEquals(
            Verdict.STALE,
            evaluate(advertAgeMs = OobScanFilter.MAX_ADVERT_AGE_MS + 1),
        )
    }

    @Test
    fun `advert exactly at max age is still accepted`() {
        assertEquals(Verdict.APPLY, evaluate(advertAgeMs = OobScanFilter.MAX_ADVERT_AGE_MS))
    }

    @Test
    fun `rssi below floor is weak`() {
        assertEquals(Verdict.WEAK, evaluate(rssiDbm = OobScanFilter.MIN_RSSI_DBM - 1))
    }

    @Test
    fun `rssi exactly at floor is still accepted`() {
        assertEquals(Verdict.APPLY, evaluate(rssiDbm = OobScanFilter.MIN_RSSI_DBM))
    }

    @Test
    fun `same payload as last applied is duplicate (no input flicker)`() {
        assertEquals(
            Verdict.DUPLICATE,
            evaluate(lastAppliedPayload = payload.copyOf()),
        )
    }

    @Test
    fun `changed payload after a previous apply is applied again`() {
        val changed: ByteArray = payload.copyOf().also { it[3] = 0x2B } // session 42 → 43
        assertEquals(Verdict.APPLY, evaluate(serviceData = changed, lastAppliedPayload = payload))
    }

    @Test
    fun `malformed wins over duplicate check even when last payload matches prefix`() {
        assertEquals(
            Verdict.MALFORMED,
            evaluate(serviceData = payload.copyOfRange(0, 6), lastAppliedPayload = payload),
        )
    }
}
