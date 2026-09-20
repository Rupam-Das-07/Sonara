package com.example.sonara.core.error

/**
 * Normalized domain exception hierarchy for Sonara Android.
 * Prevents HTTP/network-specific exceptions from leaking into UI/domain layers.
 */
sealed class SonaraException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkException(message: String = "Network connection failed", cause: Throwable? = null) :
        SonaraException(message, cause)

    class ProviderUnavailableException(message: String = "Music provider unavailable", cause: Throwable? = null) :
        SonaraException(message, cause)

    class ParsingException(message: String = "Failed to parse provider response", cause: Throwable? = null) :
        SonaraException(message, cause)

    class NotFoundException(message: String = "Requested content not found", cause: Throwable? = null) :
        SonaraException(message, cause)
}
