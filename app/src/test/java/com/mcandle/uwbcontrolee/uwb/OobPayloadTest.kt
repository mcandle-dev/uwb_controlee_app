package com.mcandle.uwbcontrolee.uwb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * OOB_INFO 7B 페이로드 인코딩 검증 — docs/oob/BLE_OOB_인터페이스_사양서.md §4 예시값 기준.
 * 바이트 순서(주소=반전 없음, session_id=LE)가 이 도메인 최대 함정이므로 리터럴 배열로 대조한다.
 */
class OobPayloadTest {

    @Test
    fun `spec example address 5F DD and session 42`() {
        val payload: ByteArray = UwbDefaults.buildOobPayload(
            uwbAddress = byteArrayOf(0x5F, 0xDD.toByte()),
            sessionId = 42,
        )
        assertArrayEquals(
            byteArrayOf(0x01, 0x5F, 0xDD.toByte(), 0x2A, 0x00, 0x00, 0x00),
            payload,
        )
    }

    @Test
    fun `payload is always 7 bytes`() {
        val payload: ByteArray = UwbDefaults.buildOobPayload(byteArrayOf(0x00, 0x00), 1)
        assertEquals(UwbDefaults.OOB_PAYLOAD_SIZE, payload.size)
    }

    @Test
    fun `first byte is always protocol version 0x01`() {
        val payload: ByteArray = UwbDefaults.buildOobPayload(byteArrayOf(0x12, 0x34), 999)
        assertEquals(UwbDefaults.OOB_PROTOCOL_VERSION, payload[0])
    }

    @Test
    fun `address bytes are copied in display order without reversal`() {
        val payload: ByteArray = UwbDefaults.buildOobPayload(byteArrayOf(0x0A, 0x3F), 42)
        assertEquals(0x0A.toByte(), payload[1])
        assertEquals(0x3F.toByte(), payload[2])
    }

    @Test
    fun `session id is little-endian across all four bytes, not just the low byte`() {
        // 0x01020304 -> LE bytes: 04 03 02 01 (offsets 3..6)
        val payload: ByteArray = UwbDefaults.buildOobPayload(
            uwbAddress = byteArrayOf(0x00, 0x00),
            sessionId = 0x01020304,
        )
        assertArrayEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01),
            payload.copyOfRange(3, 7),
        )
    }

    @Test
    fun `rejects address that is not exactly 2 bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            UwbDefaults.buildOobPayload(byteArrayOf(0x01, 0x02, 0x03), 42)
        }
    }
}
