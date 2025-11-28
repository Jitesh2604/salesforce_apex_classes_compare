package com.example.sf.service;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.net.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class SalesforceApexService {

    private static final String METADATA_URL_SUFFIX = "/services/Soap/m/57.0";
    private static final Logger LOG = LoggerFactory.getLogger(SalesforceApexService.class);

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String startRetrieve(String token, String instanceUrl) throws Exception {
        String payload = """
            {
              "apiVersion": 57.0,
              "singlePackage": true,
              "unpackaged": {
                "types": [
                  { "name": "ApexClass", "members": ["*"] }
                ]
              }
            }
            """;

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(instanceUrl + "/services/data/v57.0/metadata/retrieve"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IllegalStateException("Metadata retrieve failed: status=" + res.statusCode() + " body=" + res.body());
        }
        
        return mapper.readTree(res.body()).path("retrieveRequestId").asText();
    }

    public byte[] pollRetrieve(String token, String instanceUrl, String retrieveId) throws Exception {
        while (true) {
            Thread.sleep(1500);
            HttpRequest check = HttpRequest.newBuilder()
                .uri(URI.create(instanceUrl + "/services/data/v57.0/metadata/retrieveResult?retrieveRequestId=" + retrieveId))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

            HttpResponse<String> resp = http.send(check, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            String status = json.path("status").asText();

            LOG.info("Retrieve status: {}", status);

            if ("Succeeded".equalsIgnoreCase(status)) {
                String b64 = json.path("zipFile").asText();
                byte[] zipBytes = Base64.getDecoder().decode(b64);
                return zipBytes;
            }
            if ("Failed".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Retrieve Failed: " + json.path("errorMessage").asText());
            }
        }
    }

    public Map<String, String> extractCls(byte[] zipBytes) throws Exception {
        Map<String, String> classes = new HashMap<>();
        
        Path newDir = Path.of("storage", "apex", "new");
        Path oldDir = Path.of("storage", "apex", "old");
        if (!Files.exists(newDir)) Files.createDirectories(newDir);
        if (!Files.exists(oldDir)) Files.createDirectories(oldDir);
        
        String timestamp = String.valueOf(System.currentTimeMillis());
        int extractedCount = 0;
        int archivedCount = 0;
        
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String entryName = e.getName();
                
                // Extract all files preserving folder structure
                if (!e.isDirectory()) {
                    Path targetFile = newDir.resolve(entryName);
                    Path parentDir = targetFile.getParent();
                    if (parentDir != null && !Files.exists(parentDir)) {
                        Files.createDirectories(parentDir);
                    }
                    
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zin.transferTo(out);
                    byte[] fileBytes = out.toByteArray();
                    String newContent = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                    
                    // Compare content if file exists
                    if (Files.exists(targetFile)) {
                        String existingContent = Files.readString(targetFile, java.nio.charset.StandardCharsets.UTF_8);
                        
                        // If content differs, archive the old file with timestamp
                        if (!existingContent.equals(newContent)) {
                            // Create archive path with timestamp in filename
                            String fileName = targetFile.getFileName().toString();
                            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                            String extension = fileName.substring(fileName.lastIndexOf('.'));
                            String archivedFileName = baseName + "_" + timestamp + extension;
                            
                            // Preserve folder structure in old/
                            Path relativePath = newDir.relativize(targetFile.getParent());
                            Path oldSubDir = oldDir.resolve(relativePath);
                            if (!Files.exists(oldSubDir)) Files.createDirectories(oldSubDir);
                            
                            // Delete all previous archived versions of this file
                            deleteOldArchivedVersions(oldSubDir, baseName, extension);
                            
                            Path archivedFile = oldSubDir.resolve(archivedFileName);
                            Files.copy(targetFile, archivedFile, StandardCopyOption.REPLACE_EXISTING);
                            archivedCount++;
                            LOG.info("📦 Archived changed file: {} → {}", entryName, archivedFile.getFileName());
                        }
                    }
                    
                    // Write the new content
                    Files.write(targetFile, fileBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    extractedCount++;
                    
                    // Collect .cls files in map
                    if (entryName.endsWith(".cls")) {
                        String className = Paths.get(entryName).getFileName().toString().replace(".cls", "");
                        classes.put(className, newContent);
                        LOG.info("✅ Extracted .cls: {} ({} bytes)", entryName, fileBytes.length);
                    }
                }
            }
        }
        
        LOG.info("✅ Extracted {} files to {}", extractedCount, newDir.toAbsolutePath());
        if (archivedCount > 0) {
            LOG.info("📦 Archived {} changed files to {} with timestamp {}", archivedCount, oldDir.toAbsolutePath(), timestamp);
        }
        LOG.info("✅ Found {} Apex classes", classes.size());
        
        return classes;
    }

    public Map<String, String> retrieveAllApexClasses(String token, String instanceUrl) throws Exception {
        String retrieveId = startRetrieve(token, instanceUrl);
        byte[] zipBytes = pollRetrieve(token, instanceUrl, retrieveId);
        return extractCls(zipBytes);
    }

    public Map<String, String> waitForRetrieveAndDownload(
          String asyncId,
          String token,
          String instanceUrl
        ) throws Exception {

        while (true) {

            String checkEnvelope = """
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                      xmlns:met="http://soap.sforce.com/2006/04/metadata">
                      <soapenv:Header>
                        <met:SessionHeader><met:sessionId>REPLACE</met:sessionId></met:SessionHeader>
                      </soapenv:Header>
                      <soapenv:Body>
                        <met:checkRetrieveStatus>
                          <met:asyncProcessId>%s</met:asyncProcessId>
                        </met:checkRetrieveStatus>
                      </soapenv:Body>
                    </soapenv:Envelope>
                    """.formatted(asyncId).replace("REPLACE", token);

                HttpRequest checkReq = HttpRequest.newBuilder()
                  .uri(URI.create(instanceUrl + METADATA_URL_SUFFIX))
                    .header("Content-Type", "text/xml")
                    .header("SOAPAction", "retrieve")
                    .POST(HttpRequest.BodyPublishers.ofString(checkEnvelope))
                    .build();

            HttpResponse<String> resp = http.send(checkReq, HttpResponse.BodyHandlers.ofString());

            // Log status for debugging
            try {
              LOG.info("checkRetrieveStatus response code={} snippet={}", resp.statusCode(),
                  resp.body() == null ? "" : resp.body().substring(0, Math.min(400, resp.body().length())).replaceAll("\n", " "));
            } catch (Exception ignore) {}

            String doneTag = extractTag(resp.body(), "done");
            if ("true".equalsIgnoreCase(doneTag)) {
              String zipBase64 = extractTag(resp.body(), "zipFile");
              if (zipBase64 == null) {
                // attempt a more lenient extraction if namespaces or whitespace exist
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<zipFile>(.*?)</zipFile>").matcher(resp.body());
                if (m.find()) zipBase64 = m.group(1);
              }

              if (zipBase64 == null || zipBase64.isBlank()) {
                LOG.warn("Metadata retrieve completed but no <zipFile> content found. Response length={}", resp.body() == null ? 0 : resp.body().length());
                return Collections.emptyMap();
              }

              LOG.info("zipFile base64 length={}", zipBase64.length());
              byte[] zipBytes = Base64.getDecoder().decode(zipBase64);

              // Extract ZIP structure to storage/apex/new/ with content-based archival
              // This includes unpackaged/classes/*.cls and *.cls-meta.xml files
              Path newDir = Path.of("storage", "apex", "new");
              Path oldDir = Path.of("storage", "apex", "old");
              if (!Files.exists(newDir)) Files.createDirectories(newDir);
              if (!Files.exists(oldDir)) Files.createDirectories(oldDir);
              
              String timestamp = String.valueOf(System.currentTimeMillis());
              boolean hasCls = false;
              int extractedCount = 0;
              int archivedCount = 0;
              
              try (ZipInputStream zin2 = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zin2.getNextEntry()) != null) {
                  String entryName = entry.getName();
                  
                  if (entry.isDirectory()) {
                    Path dir = newDir.resolve(entryName);
                    if (!Files.exists(dir)) Files.createDirectories(dir);
                    continue;
                  }
                  
                  // Save all files preserving folder structure with archival logic
                  Path targetFile = newDir.resolve(entryName);
                  Path parentDir = targetFile.getParent();
                  if (parentDir != null && !Files.exists(parentDir)) {
                    Files.createDirectories(parentDir);
                  }
                  
                  ByteArrayOutputStream out = new ByteArrayOutputStream();
                  zin2.transferTo(out);
                  byte[] fileBytes = out.toByteArray();
                  String newContent = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                  
                  // Content-based archival: compare with existing file
                  if (Files.exists(targetFile)) {
                    String existingContent = Files.readString(targetFile, java.nio.charset.StandardCharsets.UTF_8);
                    
                    // If content differs, archive old file with timestamp
                    if (!existingContent.equals(newContent)) {
                      String fileName = targetFile.getFileName().toString();
                      String baseName = fileName.contains(".") 
                          ? fileName.substring(0, fileName.lastIndexOf('.'))
                          : fileName;
                      String extension = fileName.contains(".")
                          ? fileName.substring(fileName.lastIndexOf('.'))
                          : "";
                      String archivedFileName = baseName + "_" + timestamp + extension;
                      
                      // Preserve folder structure in old/
                      Path relativePath = newDir.relativize(targetFile.getParent());
                      Path oldSubDir = oldDir.resolve(relativePath);
                      if (!Files.exists(oldSubDir)) Files.createDirectories(oldSubDir);
                      
                      // Delete all previous archived versions of this file
                      deleteOldArchivedVersions(oldSubDir, baseName, extension);
                      
                      Path archivedFile = oldSubDir.resolve(archivedFileName);
                      Files.copy(targetFile, archivedFile, StandardCopyOption.REPLACE_EXISTING);
                      archivedCount++;
                      LOG.info("📦 Archived changed file: {} → {}", entryName, archivedFile.getFileName());
                    }
                  }
                  
                  // Write new content
                  Files.write(targetFile, fileBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                  extractedCount++;
                  
                  if (entryName.endsWith(".cls")) {
                    hasCls = true;
                    LOG.info("Extracted .cls file: {} ({} bytes)", entryName, fileBytes.length);
                  } else if (entryName.endsWith(".cls-meta.xml")) {
                    LOG.info("Extracted .cls-meta.xml file: {} ({} bytes)", entryName, fileBytes.length);
                  }
                }
              } catch (Exception ex) {
                LOG.error("Error extracting ZIP to storage/apex/new/: {}", ex.getMessage(), ex);
                throw ex;
              }
              
              LOG.info("✅ Extracted {} files to {}", extractedCount, newDir.toAbsolutePath());
              if (archivedCount > 0) {
                LOG.info("📦 Archived {} changed files to {} with timestamp {}", archivedCount, oldDir.toAbsolutePath(), timestamp);
              }

              if (!hasCls) {
                String msg = "Metadata retrieve returned no Apex class files. " +
                    "Likely causes: the sessionId used for the Metadata API does not have Metadata permissions, or the connected app scopes do not include API/metadata access. " +
                    "Ensure the OAuth token has the required scopes (e.g., 'api' or 'full'), the user has permission to retrieve metadata, and retry using a session with Metadata API access.";
                LOG.error(msg);
                throw new IllegalStateException(msg);
              }

              Map<String, String> results = new HashMap<>();
              try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                  String name = entry.getName();
                  if (name.endsWith(".cls")) {
                    String[] parts = name.split("/");
                    String file = parts[parts.length - 1];
                    String className = file.replaceAll("\\.cls$", "");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zin.transferTo(out);
                    String content = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    results.put(className, content);
                  }
                }
              }
              return results;
            }

            Thread.sleep(1000);
        }
    }

    private String extractTag(String xml, String tag) {
        int s = xml.indexOf("<" + tag + ">");
        if (s < 0) return null;
        int e = xml.indexOf("</" + tag + ">", s);
        if (e < 0) return null;
        return xml.substring(s + tag.length() + 2, e);
    }

    /**
     * Delete all previous archived versions of a file before saving the new one.
     * Pattern: ClassName_*.cls
     */
    private void deleteOldArchivedVersions(Path directory, String baseName, String extension) {
        try {
            if (!Files.exists(directory)) return;
            
            String pattern = baseName + "_";
            try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
                paths.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(pattern) && name.endsWith(extension);
                })
                .forEach(p -> {
                    try {
                        Files.delete(p);
                        LOG.info("🗑️  Deleted old archived version: {}", p.getFileName());
                    } catch (Exception e) {
                        LOG.warn("Failed to delete old archived file {}: {}", p.getFileName(), e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOG.error("Error deleting old archived versions: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getApexClassList(String token, String instanceUrl) throws Exception {
        String envelope = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:met="http://soap.sforce.com/2006/04/metadata">
                  <soapenv:Header>
                    <met:SessionHeader><met:sessionId>REPLACE</met:sessionId></met:SessionHeader>
                  </soapenv:Header>
                  <soapenv:Body>
                    <met:listMetadata>
                      <met:queries>
                        <met:type>ApexClass</met:type>
                      </met:queries>
                      <met:asOfVersion>57.0</met:asOfVersion>
                    </met:listMetadata>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.replace("REPLACE", token);
        String raw = listMetadataRaw(token, instanceUrl, envelope);

        if (raw == null) return Collections.emptyList();

        List<Map<String, Object>> out = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?s)<fileProperties>(.*?)</fileProperties>");
        java.util.regex.Matcher m = p.matcher(raw);
        int excludedManaged = 0;
        while (m.find()) {
          String block = m.group(1);
          String fullName = extractTag(block, "fullName");
          if (fullName == null) fullName = extractTag(block, "fileName");
          String namespace = extractTag(block, "namespacePrefix");
          String manageable = extractTag(block, "manageableState");

          // Exclude classes that belong to an installed managed package (namespace present
          // or manageableState=installed) because their source is not retrievable.
          boolean isManaged = (namespace != null && !namespace.isBlank()) || (manageable != null && "installed".equalsIgnoreCase(manageable));
          if (isManaged) {
            excludedManaged++;
            continue;
          }

          if (fullName != null) {
            Map<String, Object> map = new HashMap<>();
            map.put("Id", fullName);
            map.put("Name", fullName);
            out.add(map);
          }
        }

        if (excludedManaged > 0) {
          LOG.info("Excluded {} managed/package ApexClass entries from listMetadata results (managed packages cannot expose source).", excludedManaged);
        }

        return out;
    }

    public String listMetadataRaw(String token, String instanceUrl, String envelope) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(instanceUrl + METADATA_URL_SUFFIX))
          .header("Content-Type", "text/xml")
          .header("SOAPAction", "listMetadata")
          .POST(HttpRequest.BodyPublishers.ofString(envelope))
          .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOG.warn("listMetadata failed: status={} body={}", response.statusCode(), response.body());
            return response.body();
        }
        return response.body();
    }


      public Map<String, Object> pingInstance(String token, String instanceUrl) {
        Map<String, Object> out = new HashMap<>();
        try {
          String url = instanceUrl + "/services/data/v57.0/";
          HttpRequest req = HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build();

          HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
          out.put("ok", resp.statusCode() >= 200 && resp.statusCode() < 300);
          out.put("status", resp.statusCode());
          String body = resp.body();
          if (body != null && body.length() > 500) body = body.substring(0, 500) + "...";
          out.put("bodyPreview", body == null ? "" : body);
        } catch (Exception ex) {
          out.put("ok", false);
          out.put("exception", ex.getClass().getName());
          out.put("message", ex.getMessage());
          // capture full stacktrace
          try {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            out.put("stacktrace", sw.toString());
          } catch (Exception ignore) {
          }
        }
        return out;
      }

    public String getApexClassById(String className, String token, String instanceUrl) throws Exception {
      String envelope = """
              <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                xmlns:met="http://soap.sforce.com/2006/04/metadata">
                <soapenv:Header>
                  <met:SessionHeader>
                    <met:sessionId>%s</met:sessionId>
                  </met:SessionHeader>
                </soapenv:Header>
                <soapenv:Body>
                  <met:retrieve>
                    <met:retrieveRequest>
                      <met:apiVersion>57.0</met:apiVersion>
                      <met:singlePackage>false</met:singlePackage>
                      <met:unpackaged>
                        <met:types>
                          <met:members>%s</met:members>
                          <met:name>ApexClass</met:name>
                        </met:types>
                        <met:version>57.0</met:version>
                      </met:unpackaged>
                    </met:retrieveRequest>
                  </met:retrieve>
                </soapenv:Body>
              </soapenv:Envelope>
              """.formatted(token, className);

      HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(instanceUrl + METADATA_URL_SUFFIX))
        .header("Content-Type", "text/xml")
        .header("SOAPAction", "retrieve")
        .POST(HttpRequest.BodyPublishers.ofString(envelope))
        .build();

      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      String asyncId = extractTag(response.body(), "id");
      
      if (asyncId == null || asyncId.isBlank()) {
        return "{}";
      }

      Map<String, String> result = waitForRetrieveAndDownload(asyncId, token, instanceUrl);
      String body = result.get(className);
      
      if (body == null || body.isBlank()) {
        return "{}";
      }

      Map<String, String> jsonResult = new HashMap<>();
      jsonResult.put("Name", className);
      jsonResult.put("Body", body);
      return mapper.writeValueAsString(jsonResult);
    }

    public String sendRetrieveRequestAndGetId(String token, String instanceUrl) throws Exception {
        String envelope = """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                                  xmlns:met="http://soap.sforce.com/2006/04/metadata">
                  <soapenv:Header>
                    <met:SessionHeader>
                      <met:sessionId>%s</met:sessionId>
                    </met:SessionHeader>
                  </soapenv:Header>
                  <soapenv:Body>
                    <met:retrieve>
                      <met:retrieveRequest>
                        <met:apiVersion>58.0</met:apiVersion>
                        <met:unpackaged>
                          <met:types>
                            <met:members>*</met:members>
                            <met:name>ApexClass</met:name>
                          </met:types>
                        </met:unpackaged>
                        <met:singlePackage>false</met:singlePackage>
                      </met:retrieveRequest>
                    </met:retrieve>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(token);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(instanceUrl + METADATA_URL_SUFFIX))
                .header("Content-Type", "text/xml")
                .header("SOAPAction", "retrieve")
                .POST(HttpRequest.BodyPublishers.ofString(envelope))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        
        // Extract retrieve ID from response
        String retrieveId = extractTag(response.body(), "id");
        
        if (retrieveId == null || retrieveId.isBlank()) {
            throw new IllegalStateException("No retrieve ID returned. Response: " + 
                response.body().substring(0, Math.min(500, response.body().length())));
        }
        
        return retrieveId;
    }
}
