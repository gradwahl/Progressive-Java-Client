package com.gradwahl.rs254.net

data class LoginResult(
    private val responseValue: Int,
    private val staffModLevelValue: Int,
    private val mouseTrackingValue: Boolean
) {
    fun response(): Int = responseValue

    fun staffModLevel(): Int = staffModLevelValue

    fun mouseTracking(): Boolean = mouseTrackingValue

    fun success(): Boolean = responseValue == 2

    override fun toString(): String = when (responseValue) {
        2 -> "Login OK. staff=$staffModLevelValue, mouseTracking=$mouseTrackingValue"
        3 -> "Invalid username or password."
        4 -> "Account disabled."
        5 -> "Already logged in."
        6 -> "Client out of date / revision or CRC mismatch."
        7 -> "World full."
        16 -> "Too many login attempts."
        else -> "Login response $responseValue"
    }
}
