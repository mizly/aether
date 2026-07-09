package dev.aether.proxy;

import com.google.gson.annotations.SerializedName;
import dev.aether.util.AetherLang;

import java.net.InetSocketAddress;
import java.util.Locale;

public final class AetherProxy {
    @SerializedName("name")
    private String name = "";
    @SerializedName("address")
    private String address = "";
    @SerializedName("type")
    private ProxyType type = ProxyType.SOCKS5;
    @SerializedName("username")
    private String username = "";
    @SerializedName("password")
    private String password = "";

    public AetherProxy() {
    }

    public AetherProxy(String name, String address, ProxyType type, String username, String password) {
        this.name = clean(name);
        this.address = clean(address);
        this.type = type == null ? ProxyType.SOCKS5 : type;
        this.username = clean(username);
        this.password = clean(password);
    }

    public String name() {
        return name == null || name.isBlank() ? address() : name;
    }

    public String address() {
        return clean(address);
    }

    public ProxyType type() {
        return type == null ? ProxyType.SOCKS5 : type;
    }

    public String username() {
        return clean(username);
    }

    public String password() {
        return clean(password);
    }

    public String host() {
        HostPort hostPort = parseAddress(address());
        return hostPort == null ? "" : hostPort.host;
    }

    public int port() {
        HostPort hostPort = parseAddress(address());
        return hostPort == null ? -1 : hostPort.port;
    }

    public boolean isValid() {
        return parseAddress(address()) != null;
    }

    public InetSocketAddress socketAddress() {
        HostPort hostPort = parseAddress(address());
        if (hostPort == null) {
            throw new IllegalStateException("Invalid proxy address: " + address());
        }
        return new InetSocketAddress(hostPort.host, hostPort.port);
    }

    public String displayName() {
        String label = name();
        return label.isBlank() ? AetherLang.localize("Unnamed Proxy") : label;
    }

    public String shortStatus() {
        if (!isValid()) {
            return AetherLang.localize("invalid");
        }
        return type().displayName() + " " + address();
    }

    public AetherProxy copy() {
        return new AetherProxy(name(), address(), type(), username(), password());
    }

    public static boolean isValidAddress(String value) {
        return parseAddress(value) != null;
    }

    private static HostPort parseAddress(String value) {
        String text = clean(value);
        if (text.isBlank()) {
            return null;
        }

        int separator = text.lastIndexOf(':');
        if (separator <= 0 || separator == text.length() - 1) {
            return null;
        }

        String host = text.substring(0, separator).trim();
        String portText = text.substring(separator + 1).trim();
        if (host.isBlank() || portText.isBlank()) {
            return null;
        }

        try {
            int port = Integer.parseInt(portText);
            if (port < 0 || port > 0xFFFF) {
                return null;
            }
            return new HostPort(host, port);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record HostPort(String host, int port) {
    }

    public enum ProxyType {
        SOCKS4("Socks 4"),
        SOCKS5("Socks 5");

        private final String displayName;

        ProxyType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return AetherLang.localize(displayName);
        }

        public ProxyType next() {
            return this == SOCKS4 ? SOCKS5 : SOCKS4;
        }

        public static ProxyType fromDisplayName(String value) {
            String normalized = clean(value).toUpperCase(Locale.ROOT).replace(" ", "");
            return "SOCKS4".equals(normalized) ? SOCKS4 : SOCKS5;
        }
    }
}
