package com.example.mastermechanic.capture

/** 采集会话状态（ADR-001）：采集为会话制——每次授权对应一次会话，终止后需重新授权。 */
enum class CaptureSessionStatus { INACTIVE, ACTIVE }
