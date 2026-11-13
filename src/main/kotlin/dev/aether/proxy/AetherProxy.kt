package dev.aether.proxy

import com.google.gson.annotations.SerializedName
import dev.aether.util.AetherLang.Companion.localize
import java.net.InetSocketAddress
import java.util.Locale

class AetherProxy {
    @SerializedName("name")
    private var name: String? = ""
    @SerializedName("address")
    private var address: String? = ""
    @SerializedName("type")
    private var type: ProxyType? = ProxyType.SOCKS5
    @SerializedName("username")
    private var username: String? = ""
    @SerializedName("password")
    private var password: String? = ""

    constructor() {
    }

    constructor(name: String, address: String, type: ProxyType?, username: String, password: String) {
        this.name = clean(name)
        this.address = clean(address)
        this.type = if (type == null) ProxyType.SOCKS5 else type
        this.username = clean(username)
        this.password = clean(password)
    }

    fun name(): String {
        return if (name == null || name!!.isBlank()) address() else name!!
    }

    fun address(): String {
        return clean(address)
    }

    fun type(): ProxyType {
        return if (type == null) ProxyType.SOCKS5 else type!!
    }

    fun username(): String {
        return clean(username)
    }

    fun password(): String {
        return clean(password)
    }

    fun host(): String {
        val hostPort = parseAddress(address())
        return if (hostPort == null) "" else hostPort.host
    }

    fun port(): Int {
        val hostPort = parseAddress(address())
        return if (hostPort == null) -1 else hostPort.port
    }

    fun isValid(): Boolean {
        return parseAddress(address()) != null
    }

    fun socketAddress(): InetSocketAddress {
        val hostPort = parseAddress(address())
        if (hostPort == null) {
            throw IllegalStateException("Invalid proxy address: " + address())
        }
        return InetSocketAddress(hostPort.host, hostPort.port)
    }

    fun displayName(): String {
        val label = name()
        return if (label.isBlank()) localize("Unnamed Proxy") else label
    }

    fun shortStatus(): String {
        if (!isValid()) {
            return localize("invalid")
        }
        return type().displayName() + " " + address()
    }

    fun copy(): AetherProxy {
        return AetherProxy(name(), address(), type(), username(), password())
    }

    private class HostPort(val host: String, val port: Int)

    enum class ProxyType(private val displayName: String) {
        SOCKS4("Socks 4"),
        SOCKS5("Socks 5");

        fun displayName(): String {
            return localize(displayName)
        }

        fun next(): ProxyType {
            return if (this == SOCKS4) SOCKS5 else SOCKS4
        }

        companion object {
            @JvmStatic
            fun fromDisplayName(value: String): ProxyType {
                val normalized = clean(value).uppercase(Locale.ROOT).replace(" ", "")
                return if ("SOCKS4" == normalized) SOCKS4 else SOCKS5
            }
        }
    }

    companion object {
        @JvmStatic
        fun isValidAddress(value: String): Boolean {
            return parseAddress(value) != null
        }

        private fun parseAddress(value: String?): HostPort? {
            val text = clean(value)
            if (text.isBlank()) {
                return null
            }

            val separator = text.lastIndexOf(':')
            if (separator <= 0 || separator == text.length - 1) {
                return null
            }

            val host = text.substring(0, separator).trim()
            val portText = text.substring(separator + 1).trim()
            if (host.isBlank() || portText.isBlank()) {
                return null
            }

            try {
                val port = Integer.parseInt(portText)
                if (port < 0 || port > 0xFFFF) {
                    return null
                }
                return HostPort(host, port)
            } catch (ignored: NumberFormatException) {
                return null
            }
        }

        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
