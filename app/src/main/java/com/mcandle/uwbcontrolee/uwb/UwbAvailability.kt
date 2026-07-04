package com.mcandle.uwbcontrolee.uwb

/**
 * UWB 가용성 4-상태 + 초기 확인중 상태 (FR-1).
 * 판정 순서: 하드웨어 → 권한 → 어댑터(토글) 순으로 좁힌다.
 */
enum class UwbAvailability {
    /** 아직 확인 전 (앱 시작 직후) */
    CHECKING,

    /** 기기에 UWB 하드웨어 없음 (PackageManager.FEATURE_UWB false) */
    NOT_SUPPORTED,

    /** UWB_RANGING 런타임 권한 미획득 */
    PERMISSION_DENIED,

    /** 하드웨어·권한 OK, 설정의 UWB(초광대역) 토글이 꺼짐 (또는 리전 펌웨어 비활성) */
    DISABLED,

    /** 사용 가능 */
    READY,
}
