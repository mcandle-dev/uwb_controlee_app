package com.mcandle.uwbcontrolee.uwb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 모드 4 (GATT 역방향) 계약 스냅샷 (spec 002 T201/T204 — 사양서 v0.5 §3-1·§4).
 * UUID 리터럴과 payload 양방향(BOARD_INFO Read ← 파서 / PHONE_INFO Write ← 빌더)의
 * 의미를 고정한다 — 상수 오타·바이트 순서 회귀를 JVM 에서 잡는다 (P3).
 */
class Mode4ContractTest {

    @Test
    fun `mode 4 gatt service reuses console direction uuid 5F1D0003`() {
        assertEquals(
            "5f1d0003-9a8b-4c7d-b2e3-6f4a5d8c9b0a",
            UwbDefaults.ADV_INFO_UUID.toString(),
        )
    }

    @Test
    fun `characteristic uuids match spec v0_5 section 3-1`() {
        assertEquals(
            "5f1d0004-9a8b-4c7d-b2e3-6f4a5d8c9b0a",
            UwbDefaults.BOARD_INFO_CHARACTERISTIC_UUID.toString(),
        )
        assertEquals(
            "5f1d0005-9a8b-4c7d-b2e3-6f4a5d8c9b0a",
            UwbDefaults.PHONE_INFO_CHARACTERISTIC_UUID.toString(),
        )
    }

    @Test
    fun `board info read path - parse board mac and session id (spec section 4 mode 4 row)`() {
        // 콘솔이 BOARD_INFO 로 주는 것: uwb_address = 보드 MAC (모드 3 과 동일 의미)
        val fromConsole: ByteArray =
            byteArrayOf(0x01, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00) // 보드 00:00, session 42
        val info: OobInfo? = UwbDefaults.parseOobPayload(fromConsole)
        assertEquals(OobInfo(protocolVersion = 1, addressHex = "00:00", sessionId = 42), info)
    }

    @Test
    fun `phone info write path - build phone address payload (spec section 4 mode 4 row)`() {
        // 폰이 PHONE_INFO 로 쓰는 것: uwb_address = 폰 주소 (모드 1·2 와 동일 의미)
        val toConsole: ByteArray =
            UwbDefaults.buildOobPayload(byteArrayOf(0x5F, 0xDD.toByte()), 42)
        assertArrayEquals(
            byteArrayOf(0x01, 0x5F, 0xDD.toByte(), 0x2A, 0x00, 0x00, 0x00),
            toConsole,
        )
    }

    @Test
    fun `read then write round-trips through the shared v1 payload (no new format)`() {
        // 모드 4 는 새 포맷이 없다 — 빌더/파서 v1 재사용이 계약 (P3, §4 "모든 모드 공통")
        val payload: ByteArray = UwbDefaults.buildOobPayload(byteArrayOf(0x0A, 0x3F), 7)
        val parsed: OobInfo? = UwbDefaults.parseOobPayload(payload)
        assertEquals("0A:3F", parsed?.addressHex)
        assertEquals(7, parsed?.sessionId)
    }

    @Test
    fun `central mode storage value is stable`() {
        assertEquals("CENTRAL", OobMode.CENTRAL.storageValue)
        assertEquals(OobMode.CENTRAL, OobMode.fromStorageValue("CENTRAL"))
        // 기존 3모드의 저장값이 CENTRAL 추가로 변하지 않았다 (prefs 하위호환 — P10)
        assertEquals(OobMode.ADVERTISE_GATT, OobMode.fromStorageValue("ADVERTISE_GATT"))
    }
}
