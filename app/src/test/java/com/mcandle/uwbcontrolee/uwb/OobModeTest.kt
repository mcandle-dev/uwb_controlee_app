package com.mcandle.uwbcontrolee.uwb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OOB 모드 영속화 매핑 검증 (spec 001 T102) — SharedPreferences 는 문자열만 저장하므로
 * 저장값↔모드 순수 매핑을 JVM 에서 잡는다 (P3). 기본값 규칙: 사양서 §2 (v1 경로가 회귀 기준).
 */
class OobModeTest {

    @Test
    fun `default mode is ADVERTISE_GATT`() {
        assertEquals(OobMode.ADVERTISE_GATT, OobMode.DEFAULT)
    }

    @Test
    fun `every mode round-trips through its storage value`() {
        OobMode.entries.forEach { mode ->
            assertEquals(mode, OobMode.fromStorageValue(mode.storageValue))
        }
    }

    @Test
    fun `null storage value falls back to default`() {
        assertEquals(OobMode.DEFAULT, OobMode.fromStorageValue(null))
    }

    @Test
    fun `unknown storage value falls back to default`() {
        assertEquals(OobMode.DEFAULT, OobMode.fromStorageValue("MULTICAST"))
    }

    @Test
    fun `storage values are stable contract strings`() {
        // prefs 에 이미 저장된 값과의 호환 — enum 이름 변경이 조용히 모드를 리셋하지 않도록 고정
        assertEquals("ADVERTISE_GATT", OobMode.ADVERTISE_GATT.storageValue)
        assertEquals("BEACON", OobMode.BEACON.storageValue)
        assertEquals("SCANNER", OobMode.SCANNER.storageValue)
    }
}
