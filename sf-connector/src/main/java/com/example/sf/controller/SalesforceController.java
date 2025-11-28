package com.example.sf.controller;

import com.example.sf.service.SalesforceApexService;
import com.example.sf.service.SalesforceAuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;


@RestController
public class SalesforceController {

    private final SalesforceAuthService authService;
    private final SalesforceApexService apexService;

    @Value("${salesforce.clientId}")
    private String clientId;

    @Value("${salesforce.redirectUri}")
    private String redirectUri;

    @Value("${salesforce.authUrl}")
    private String authUrl;
    @Value("${salesforce.scope:full refresh_token api}")
    private String scope;

    public SalesforceController(SalesforceAuthService authService,
                                SalesforceApexService apexService) {
        this.authService = authService;
        this.apexService = apexService;
    }

    // STEP 1: USER OPENS /connect AND GOES TO SALESFORCE LOGIN    
    @GetMapping("/connect")
    public Mono<ResponseEntity<Void>> connect() {

        URI redirect = UriComponentsBuilder.fromHttpUrl(authUrl)
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type", "code")
            .queryParam("scope", scope)
            .queryParam("state", "xyz123")
            .build()
            .encode()
            .toUri();

        return Mono.just(
            ResponseEntity.status(HttpStatus.FOUND)
                .location(redirect)
                .build()
        );
    }

    @GetMapping("/check-api")
    public Mono<Object> checkApi(HttpSession session) {
        String token = (String) session.getAttribute("sf_access_token");
        String instance = (String) session.getAttribute("sf_instance_url");

        if (token == null || instance == null) {
            return Mono.just(Map.of("error", "not_connected", "message", "Not connected. Visit /connect"));
        }

        return authService.verifyApiAccess(token, instance).cast(Object.class);
    }

    // STEP 2: SALESFORCE RETURNS TO /callback WITH "code"
    // Legacy callback path kept for compatibility
    @GetMapping({"/callback", "/oauth/callback"})
            public Mono<Object> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            HttpSession session
    ) {

            if (code == null || code.isBlank()) {
                System.out.println("❌ Callback error: No authorization code received.");
                return Mono.just(Map.of(
                        "error", "no_code",
                        "message", "No authorization code received. Start OAuth by visiting /connect"
                ));
            }

            System.out.println("✔ Authorization code received: " + code);

                // Exchange the authorization code for token and store into session
                return authService.exchangeCodeForToken(code, session)
                        .map(msg -> {
                            // remove legacy keys if present
                            try {
                                session.removeAttribute("access_token");
                                session.removeAttribute("instance_url");
                            } catch (Exception ignored) {}

                            // ensure the canonical keys are used
                            String token = (String) session.getAttribute("sf_access_token");
                            String instance = (String) session.getAttribute("sf_instance_url");

                            Map<String, Object> body = new java.util.LinkedHashMap<>();
                            body.put("message", msg);
                            body.put("connected", token != null && instance != null);
                            if (token != null) body.put("tokenPreview", "****" + token.substring(Math.max(0, token.length() - 6)));
                            if (instance != null) body.put("instanceUrl", instance);

                            return body;
                        })
                        .onErrorResume(ex -> {
                            String err = ex == null ? "unknown_error" : (ex.getMessage() == null ? ex.toString() : ex.getMessage());
                            System.err.println("⚠ Error exchanging code: " + err);
                            return Mono.just(Map.of("error", "token_exchange_failed", "detail", err));
                        })
                        .cast(Object.class);
    }
}
