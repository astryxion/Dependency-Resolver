package com.dependencyresolver.platform;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Locale;

public final class Network {
    private Network() {
    }

    public static boolean isOffline(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnknownHostException || current instanceof ConnectException || current instanceof NoRouteToHostException) {
                return true;
            }
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("network is unreachable")
                    || message.contains("name or service not known")
                    || message.contains("temporary failure in name resolution")
                    || message.contains("nodename nor servname")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
