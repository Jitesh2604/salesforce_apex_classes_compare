package com.example.sf.controller;

import com.example.sf.service.ApexComparisonService;
import com.example.sf.service.SalesforceApexService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/apex")
public class ApexController {

    private final SalesforceApexService apexService;
    private final ApexComparisonService comparisonService;

    public ApexController(SalesforceApexService apexService,
                          ApexComparisonService comparisonService) {
        this.apexService = apexService;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/classes")
    public Mono<Object> list(HttpSession session) {
        String token = (String) session.getAttribute("sf_access_token");
        String instance = (String) session.getAttribute("sf_instance_url");

        if (token == null || instance == null) {
            return Mono.just(Map.of("error", "Not connected. Visit /connect"));
        }

        try {
            List<Map<String, Object>> list = apexService.getApexClassList(token, instance);
            return Mono.just(list);
        } catch (Exception e) {
            String cls = e == null ? "UnknownException" : e.getClass().getSimpleName();
            String msg = e == null ? "unknown_error" : (e.getMessage() == null ? e.toString() : e.getMessage());
            return Mono.just(Map.of(
                    "error", "apex_list_failed",
                    "exception", cls,
                    "message", msg
            ));
        }
    }

    @GetMapping("/ping-instance")
    public Mono<Map<String, Object>> pingInstance(HttpSession session) {
        String token = (String) session.getAttribute("sf_access_token");
        String instance = (String) session.getAttribute("sf_instance_url");

        if (token == null || instance == null) {
            return Mono.just(Map.of("error", "not_connected", "message", "Not connected. Visit /connect"));
        }

        try {
            Map<String, Object> res = apexService.pingInstance(token, instance);
            return Mono.just(res);
        } catch (Exception ex) {
            String cls = ex == null ? "UnknownException" : ex.getClass().getSimpleName();
            String msg = ex == null ? "unknown_error" : (ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return Mono.just(Map.of("error", "ping_failed", "exception", cls, "message", msg));
        }
    }

    @GetMapping("/show-session")
    public Map<String, Object> showSession(HttpSession session) {
        Map<String, Object> map = new HashMap<>();
        Object sfAccess = session.getAttribute("sf_access_token");
        Object sfInstance = session.getAttribute("sf_instance_url");
        Object sfScope = session.getAttribute("sf_scope");

        map.put("sf_access_token", maskToken(sfAccess));
        map.put("sf_instance_url", sfInstance);
        map.put("sf_scope", sfScope);
        return map;
    }

    private String maskToken(Object tokenObj) {
        if (tokenObj == null) return null;
        String token = tokenObj.toString();
        if (token.length() < 10) return token;
        return token.substring(0, 5) + "..." + token.substring(token.length() - 5);
    }

    @GetMapping("/get-retrieve-id")
    public Mono<Object> getRetrieveId(HttpSession session) {
        String token = (String) session.getAttribute("sf_access_token");
        String instance = (String) session.getAttribute("sf_instance_url");

        if (token == null || instance == null) {
            return Mono.just(Map.of("error", "Not connected. Visit /connect first"));
        }

        try {
            String retrieveId = apexService.sendRetrieveRequestAndGetId(token, instance);
            
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("✅ Retrieve ID → " + retrieveId);
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            return Mono.just(Map.of(
                "success", true,
                "retrieveId", retrieveId,
                "message", "Retrieve ID printed to console"
            ));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            System.err.println("❌ Error getting retrieve ID: " + msg);
            return Mono.just(Map.of("error", "retrieve_id_failed", "message", msg));
        }
    }

    @GetMapping("/check-retrieve-status/{retrieveId}")
    public Mono<Object> checkRetrieveStatus(@PathVariable String retrieveId, HttpSession session) {
        String token = (String) session.getAttribute("sf_access_token");
        String instance = (String) session.getAttribute("sf_instance_url");

        if (token == null || instance == null) {
            return Mono.just(Map.of("error", "Not connected. Visit /connect first"));
        }

        try {
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("🔄 Polling checkRetrieveStatus for ID: " + retrieveId);
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            Map<String, String> result = apexService.waitForRetrieveAndDownload(retrieveId, token, instance);
            
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("✅ Retrieve completed successfully!");
            System.out.println("📦 Retrieved " + result.size() + " Apex classes");
            System.out.println("📂 Files extracted to: storage/apex/new/");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            return Mono.just(Map.of(
                "success", true,
                "retrieveId", retrieveId,
                "classCount", result.size(),
                "classes", result.keySet(),
                "extractedPath", Path.of("storage/apex/new").toAbsolutePath().toString(),
                "message", "Retrieve completed and extracted to storage/apex/new/"
            ));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            System.err.println("\n❌ Error checking retrieve status: " + msg + "\n");
            return Mono.just(Map.of("error", "check_retrieve_status_failed", "message", msg));
        }
    }

    @GetMapping("/retrieve-and-poll")
    public Mono<Object> retrieveAndPoll(HttpSession session) {
        String token = (String) session.getAttribute("sf_access_token");
        String instance = (String) session.getAttribute("sf_instance_url");

        if (token == null || instance == null) {
            return Mono.just(Map.of("error", "Not connected. Visit /connect first"));
        }

        try {
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("🚀 Starting Metadata API Retrieve");
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            String retrieveId = apexService.sendRetrieveRequestAndGetId(token, instance);
            System.out.println("✅ Retrieve ID → " + retrieveId + "\n");
            
            System.out.println("🔄 Polling checkRetrieveStatus (this may take a few seconds)...\n");
            Map<String, String> result = apexService.waitForRetrieveAndDownload(retrieveId, token, instance);
            
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("✅ Retrieve completed successfully!");
            System.out.println("📦 Retrieved " + result.size() + " Apex classes");
            System.out.println("📂 Files saved to: storage/apex/new/");
            System.out.println("📦 Changed files archived to: storage/apex/old/ (with timestamp)");
            if (!result.isEmpty()) {
                System.out.println("\n📋 Retrieved classes:");
                result.keySet().forEach(name -> System.out.println("   • " + name));
            }
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            
            return Mono.just(Map.of(
                "success", true,
                "retrieveId", retrieveId,
                "classCount", result.size(),
                "classes", result.keySet(),
                "extractedPath", Path.of("storage/apex/new").toAbsolutePath().toString(),
                "message", "Retrieve completed and extracted to storage/apex/new/"
            ));
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            System.err.println("\n❌ Error in retrieve and poll: " + msg + "\n");
            return Mono.just(Map.of("error", "retrieve_and_poll_failed", "message", msg));
        }
    }

    @GetMapping("/compare/{fileName}")
    public Mono<Object> compareFile(@PathVariable String fileName) {
        try {
            Map<String, Object> result = comparisonService.compareFile(fileName);
            return Mono.just(result);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return Mono.just(Map.of("error", "comparison_failed", "message", msg));
        }
    }

    @GetMapping("/compare-files")
    public Mono<Object> compareAllFiles() {
        try {
            List<Map<String, Object>> results = comparisonService.compareAll();
            return Mono.just(results);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return Mono.just(Map.of("error", "comparison_failed", "message", msg));
        }
    }

    @GetMapping("/compare-summary")
    public Mono<Object> getChangeSummary() {
        try {
            Map<String, Object> summary = comparisonService.getChangeSummary();
            return Mono.just(summary);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            return Mono.just(Map.of("error", "summary_failed", "message", msg));
        }
    }
}
