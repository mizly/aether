package dev.aether.telemetry

import java.io.IOException

// statusCode is 0 when no HTTP response happened (offline/DNS/timeout); messages never carry headers or the token.
class AetherApiException internal constructor(message: String, private val statusCodeValue: Int, errorCode: String?, cause: Throwable?) : IOException(message, cause) {
    private val errorCodeValue: String = errorCode ?: ""

    internal constructor(message: String, statusCode: Int, errorCode: String?) : this(message, statusCode, errorCode, null)

    fun statusCode(): Int = statusCodeValue
    fun errorCode(): String = errorCodeValue
    fun isUnauthorized(): Boolean = statusCodeValue == 401
    fun isNotActive(): Boolean = statusCodeValue == 409 && "not_active" == errorCodeValue
}
