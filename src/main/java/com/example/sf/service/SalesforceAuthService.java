package com.example.sf.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import java.time.Duration;
import java.net.ConnectException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SalesforceAuthService {

    @Value("${salesforce.clientId}")
    private String clientId;

    @Value("${salesforce.clientSecret}")
    private String clientSecret;

    @Value("${salesforce.redirectUri}")
    private String redirectUri;

    @Value("${salesforce.tokenUrl}")
    private String tokenUrl;

    private final WebClient webClient = WebClient.builder()
            .filter(ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
                try {
                    System.out.println("--> Salesforce Request: " + clientRequest.method() + " " + clientRequest.url());
                } catch (Exception ignored) {
                }
                return Mono.just(clientRequest);
            }))
            .build();

    // Exchange authorization code for token and store into session
    public Mono<String> exchangeCodeForToken(String code, HttpSession session) {
        if (code == null || code.isBlank()) {
            return Mono.just("⚠ No authorization code found. Refresh after login is not allowed.");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri);

        try {
            Map<String, String> masked = new LinkedHashMap<>();
            form.forEach((k, v) -> {
                if (v == null) {
                    masked.put(k, "null");
                    return;
                }
                if (k.equalsIgnoreCase("client_secret") || k.equalsIgnoreCase("code")) {
                    String s = v;
                    if (s.length() > 6) s = s.substring(0, 3) + "..." + s.substring(s.length() - 3);
                    masked.put(k, s);
                } else {
                    masked.put(k, v);
                }
            });
            System.out.println("--> Salesforce Token Request Form (masked): " + masked);
        } catch (Exception ignored) {
        }

        org.springframework.util.LinkedMultiValueMap<String, String> multi = new org.springframework.util.LinkedMultiValueMap<>();
        form.forEach((k, v) -> multi.add(k, v == null ? "" : v));

        return webClient.post()
                .uri(tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(BodyInserters.fromFormData(multi))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Map.class).map(resp -> {
                            String accessToken = (String) resp.get("access_token");
                            String instanceUrl = (String) resp.get("instance_url");

                            // store only when non-null
                            if (accessToken != null && instanceUrl != null) {
                                session.setAttribute("sf_access_token", accessToken);
                                session.setAttribute("sf_instance_url", instanceUrl);

                                // =====================================================
                                // 🔐 CHECK AND LOG OAUTH TOKEN SCOPE
                                // =====================================================
                                Object scopeObj = resp.get("scope");
                                if (scopeObj != null) {
                                    try {
                                        String scopeStr = String.valueOf(scopeObj);
                                        session.setAttribute("sf_scope", scopeStr);
                                        
                                        System.out.println("=".repeat(60));
                                        System.out.println("🔐 OAUTH TOKEN SCOPE CHECK");
                                        System.out.println("=".repeat(60));
                                        System.out.println("Granted scopes: " + scopeStr);
                                        
                                        // Check if required scopes are present
                                        boolean hasApi = scopeStr.toLowerCase().contains("api");
                                        boolean hasFull = scopeStr.toLowerCase().contains("full");
                                        
                                        if (hasApi || hasFull) {
                                            System.out.println("✅ SCOPE CHECK PASSED: Token contains required scope");
                                            if (hasFull) System.out.println("   ✓ Has 'full' scope");
                                            if (hasApi) System.out.println("   ✓ Has 'api' scope");
                                        } else {
                                            System.out.println("❌ SCOPE CHECK FAILED: Token missing 'api' or 'full' scope");
                                            System.out.println("   ⚠️  Metadata API retrieve may return empty .cls files");
                                            System.out.println("   ⚠️  Update Connected App OAuth scopes in Salesforce");
                                        }
                                        System.out.println("=".repeat(60));
                                    } catch (Exception ignore) {}
                                } else {
                                    System.out.println("=".repeat(60));
                                    System.out.println("⚠️  WARNING: No 'scope' field in OAuth token response");
                                    System.out.println("   Token response keys: " + resp.keySet());
                                    System.out.println("=".repeat(60));
                                }

                                System.out.println("✅ Token saved in session: " + (accessToken == null ? "null" : ("****" + accessToken.substring(Math.max(0, accessToken.length() - 6)))));
                                System.out.println("🌍 Instance URL saved in session: " + instanceUrl);

                                return "✔ Auth success. Token stored in session.";
                            } else {
                                System.err.println("❌ Token or instance URL missing in token response: " + resp);
                                return "❌ OAuth token response missing required fields.";
                            }
                        });
                    } else {
                        return response.bodyToMono(String.class).flatMap(body -> {
                            System.err.println("❌ Salesforce token exchange returned status " + response.statusCode() + " with body: " + body);
                            return Mono.just("❌ Salesforce OAuth Error: " + body);
                        });
                    }
                })
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(ex -> {
                    // handle connection problems and timeouts more clearly
                    // Print full stacktrace to help diagnose network/proxy/SSL issues
                    if (ex != null) {
                        System.err.println("⚠ Error during token exchange: " + ex.toString());
                        try {
                            ex.printStackTrace(System.err);
                        } catch (Exception ignore) {
                        }

                        if (ex instanceof ConnectException || (ex.getCause() != null && ex.getCause() instanceof ConnectException)) {
                            Throwable root = ex.getCause() == null ? ex : ex.getCause();
                            System.err.println("🔌 ConnectException when calling token endpoint (root): " + root);
                            return Mono.just("❌ Connection error contacting Salesforce token endpoint: " + root);
                        }

                        // For other errors (timeouts, SSLHandshakeException, etc.) return a message including the exception class
                        String msg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                        return Mono.just("❌ OAuth token exchange failed: " + msg);
                    }

                    return Mono.just("❌ OAuth token exchange failed: unknown error");
                });
    }

    /**
     * Exchange authorization code for token and return the raw response map WITHOUT storing into session.
     * Returns a Map with keys `access_token` and `instance_url` when successful, or an error message under `error`.
     */
    public Mono<Map<String, Object>> exchangeCodeForTokenRaw(String code) {
        if (code == null || code.isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "no_code");
            m.put("message", "No authorization code provided");
            return Mono.just(m);
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri);

        org.springframework.util.LinkedMultiValueMap<String, String> multi = new org.springframework.util.LinkedMultiValueMap<>();
        form.forEach((k, v) -> multi.add(k, v == null ? "" : v));

        // Method removed — use exchangeCodeForToken which stores session attributes.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "not_implemented");
        out.put("message", "exchangeCodeForTokenRaw has been removed; use exchangeCodeForToken");
        return Mono.just(out);
    }

    /**
     * Non-blocking API access verifier. Calls the userinfo endpoint and returns a map
     * with keys: ok(boolean), userinfo(Map) on success or error/message on failure.
     */
    public Mono<Map<String, Object>> verifyApiAccess(String accessToken, String instanceUrl) {
        if (accessToken == null || instanceUrl == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "not_connected");
            m.put("message", "No access token or instance URL available in session.");
            return Mono.just(m);
        }

        return webClient.get()
                .uri(instanceUrl + "/services/oauth2/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(10))
                .map(userinfo -> {
                    Map<String, Object> ok = new LinkedHashMap<>();
                    ok.put("ok", true);
                    ok.put("userinfo", userinfo);
                    return ok;
                })
                .onErrorResume(ex -> {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("ok", false);
                    err.put("error", ex.getClass().getSimpleName());
                    err.put("message", ex.getMessage());
                    return Mono.just(err);
                });
    }

}
