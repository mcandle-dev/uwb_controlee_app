package com.mcandle.uwbcontrolee.uwb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OOB_INFO 파서 검증 (spec 001 모드 3, 사양서 §4) — buildOobPayload 의 역함수.
 * 바이트 순서(주소 반전 없음, session_id LE)와 전방 호환 규칙을 리터럴로 대조한다.
 */
class OobPayloadParseTest {

    @Test
    fun `spec example round-trips - board 5F DD session 42`() {
        val payload: ByteArray =
            byteArrayOf(0x01, 0x5F, 0xDD.toByte(), 0x2A, 0x00, 0x00, 0x00)
        val info: OobInfo? = UwbDefaults.parseOobPayload(payload)
        assertEquals(OobInfo(protocolVersion = 1, addressHex = "5F:DD", sessionId = 42), info)
    }

    @Test
    fun `build then parse is identity`() {
        val built: ByteArray =
            UwbDefaults.buildOobPayload(byteArrayOf(0x0A, 0x3F), 0x01020304)
        val info: OobInfo? = UwbDefaults.parseOobPayload(built)
        assertEquals(OobInfo(protocolVersion = 1, addressHex = "0A:3F", sessionId = 0x01020304), info)
    }

    @Test
    fun `session id is read little-endian across all four bytes`() {
        val payload: ByteArray =
            byteArrayOf(0x01, 0x00, 0x00, 0x04, 0x03, 0x02, 0x01)
        assertEquals(0x01020304, UwbDefaults.parseOobPayload(payload)?.sessionId)
    }

    @Test
    fun `shorter than 7 bytes returns null`() {
        assertNull(UwbDefaults.parseOobPayload(byteArrayOf(0x01, 0x5F, 0xDD.toByte(), 0x2A)))
    }

    @Test
    fun `extra trailing bytes are ignored (forward compat)`() {
        val payload: ByteArray =
            byteArrayOf(0x01, 0x5F, 0xDD.toByte(), 0x2A, 0x00, 0x00, 0x00, 0x7F, 0x7F)
        assertEquals(42, UwbDefaults.parseOobPayload(payload)?.sessionId)
    }

    @Test
    fun `unknown version is still parsed - caller decides the warning`() {
        val payload: ByteArray =
            byteArrayOf(0x02, 0x5F, 0xDD.toByte(), 0x2A, 0x00, 0x00, 0x00)
        assertEquals(2, UwbDefaults.parseOobPayload(payload)?.protocolVersion)
    }

    @Test
    fun `high bytes format as two uppercase hex digits`() {
        val payload: ByteArray =
            byteArrayOf(0x01, 0xAB.toByte(), 0x0C, 0x2A, 0x00, 0x00, 0x00)
        assertEquals("AB:0C", UwbDefaults.parseOobPayload(payload)?.addressHex)
    }
}
