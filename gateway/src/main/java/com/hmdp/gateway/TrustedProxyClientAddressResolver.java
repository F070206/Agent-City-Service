package com.hmdp.gateway;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.server.ServerWebExchange;

/** Resolves forwarded client addresses only when the socket peer is explicitly trusted. */
final class TrustedProxyClientAddressResolver {
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private final List<Network> trustedNetworks;

    TrustedProxyClientAddressResolver(String trustedProxyCidrs) {
        trustedNetworks = parseNetworks(trustedProxyCidrs);
    }

    String resolve(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        InetAddress peerAddress = remote.getAddress();
        String peerText = peerAddress == null ? remote.getHostString() : peerAddress.getHostAddress();
        if (peerAddress == null || !isTrusted(peerAddress.getAddress())) {
            return peerText;
        }

        List<String> forwardedHeaders = exchange.getRequest().getHeaders().get("X-Forwarded-For");
        if (forwardedHeaders == null || forwardedHeaders.isEmpty()) {
            return peerText;
        }
        String forwardedFor = String.join(",", forwardedHeaders);
        if (forwardedFor.isBlank()) {
            return peerText;
        }
        List<InetAddress> chain = parseForwardedChain(forwardedFor);
        if (chain == null) {
            return peerText;
        }
        for (int index = chain.size() - 1; index >= 0; index--) {
            InetAddress address = chain.get(index);
            if (!isTrusted(address.getAddress())) {
                return address.getHostAddress();
            }
        }
        return peerText;
    }

    private boolean isTrusted(byte[] address) {
        return trustedNetworks.stream().anyMatch(network -> network.contains(address));
    }

    private List<InetAddress> parseForwardedChain(String header) {
        List<InetAddress> addresses = new ArrayList<>();
        for (String part : header.split(",", -1)) {
            InetAddress address = parseLiteral(part.trim());
            if (address == null) {
                return null;
            }
            addresses.add(address);
        }
        return addresses;
    }

    private List<Network> parseNetworks(String value) {
        List<Network> networks = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return networks;
        }
        for (String entry : value.split(",", -1)) {
            String[] parts = entry.trim().split("/", -1);
            if (parts.length > 2) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + entry);
            }
            InetAddress address = parseLiteral(parts[0].trim());
            if (address == null) {
                throw new IllegalArgumentException("Invalid trusted proxy address: " + entry);
            }
            int prefixLength = address.getAddress().length * Byte.SIZE;
            if (parts.length == 2) {
                try {
                    prefixLength = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + entry, e);
                }
            }
            if (prefixLength < 0 || prefixLength > address.getAddress().length * Byte.SIZE) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + entry);
            }
            networks.add(new Network(address.getAddress(), prefixLength));
        }
        return networks;
    }

    private InetAddress parseLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.indexOf(':') < 0) {
            if (!IPV4_LITERAL.matcher(value).matches()) {
                return null;
            }
            String[] octets = value.split("\\.");
            byte[] address = new byte[4];
            for (int index = 0; index < octets.length; index++) {
                if (octets[index].length() > 1 && octets[index].charAt(0) == '0') {
                    return null;
                }
                int octet;
                try {
                    octet = Integer.parseInt(octets[index]);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (octet > 255) {
                    return null;
                }
                address[index] = (byte) octet;
            }
            try {
                return InetAddress.getByAddress(address);
            } catch (UnknownHostException e) {
                return null;
            }
        }
        if (!IP_LITERAL.matcher(value).matches()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.getAddress().length != 16) {
                return null;
            }
            return address;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private record Network(byte[] address, int prefixLength) {
        boolean contains(byte[] candidate) {
            if (address.length != candidate.length) {
                return false;
            }
            int remaining = prefixLength;
            for (int index = 0; index < address.length; index++) {
                int bits = Math.min(remaining, Byte.SIZE);
                if (bits > 0) {
                    int mask = 0xff << (Byte.SIZE - bits) & 0xff;
                    if ((address[index] & mask) != (candidate[index] & mask)) {
                        return false;
                    }
                }
                remaining -= bits;
            }
            return true;
        }
    }
}
