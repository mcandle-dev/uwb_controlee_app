package com.mcandle.uwbcontrolee.uwb

/**
 * 레인징 세션 상태 머신 (FR-7).
 *
 * IDLE ─Start→ WAITING ─첫 측정→ RANGING ─PeerDisconnected→ DISCONNECTED
 *   ▲              │                  │─예외→ ERROR
 *   │              │─프레임워크 자동 종료(10초 무수신, Flow 정상 완료)→ ERROR
 *   │              │                  │─프레임워크 종료(측정 후)→ DISCONNECTED
 *   └────────Stop──┴────────────────┘
 *
 * '수신없음'(FR-8)은 별도 플래그(UiState.noSignal) — RANGING 상태는 유지된다.
 * DISCONNECTED/ERROR에서 Start = 재시도 (새 세션).
 */
enum class RangingState {
    IDLE,
    WAITING,
    RANGING,
    DISCONNECTED,
    ERROR,
}
