package com.hmdp.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** A small dependency-free HTTP probe used by the service container health checks. */
public final class HttpHealthcheck {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private HttpHealthcheck() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || !isHealthy(args[0])) {
            System.exit(1);
        }
    }

    static boolean isHealthy(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Health check interrupted: " + e.getMessage());
            return false;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Health check failed: " + e.getMessage());
            return false;
        }
    }
}
