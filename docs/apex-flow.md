# Apex file-based storage & compare — Code Flow

This document explains how the application fetches Apex classes from Salesforce, compares them with a locally stored copy (file-based storage), and saves updated files when changes are detected.

Scope
- Project root: `sf-connector`
- Relevant feature: "Apex file-based storage and comparison" implemented under `com.example.sf` packages

High-level flow
1. User authenticates to Salesforce using the web OAuth flow: GET `/connect` -> login -> callback `/callback`.
2. The server stores the OAuth access token and instance URL in the HTTP session (`sf_access_token`, `sf_instance_url`).
3. The user requests a list of Apex classes via GET `/apex/classes`. The app calls the Tooling API and returns JSON.
4. The user requests a single Apex class via GET `/apex/class/{Id}`. The app:
   - Calls Salesforce Tooling API (ApexClass record) to get the latest `Body` (source).
   - Reads the previously stored file (if any) from local file-based storage.
   - Compares the two sources line-by-line.
   - If differences exist, saves the new source to a file named `{apexId}_{safeName}.cls` in the configured storage directory.
   - Returns a JSON result containing whether the file changed, the diffs, and whether the save succeeded.

Files involved (code locations)
- OAuth / controllers
  - `src/main/java/com/example/sf/controller/SalesforceController.java` — `/connect` and `/callback` endpoints. Responsible for OAuth redirect and calling auth service to exchange code for tokens.
  - `src/main/java/com/example/sf/controller/ApexController.java` — `/apex/classes` and `/apex/class/{id}` endpoints. Orchestrates fetch -> compare -> save.

- Salesforce HTTP client
  - `src/main/java/com/example/sf/service/SalesforceAuthService.java` — exchanges authorization code for access token and stores `sf_access_token` and `sf_instance_url` in the HTTP session.
  - `src/main/java/com/example/sf/service/SalesforceApexService.java` — calls the Tooling API:
    - `getApexClassList(String token, String instanceUrl)` -> returns the Tooling API query JSON for listing classes.
    - `getApexClassById(String id, String token, String instanceUrl)` -> returns the ApexClass record JSON (includes `Body`).

- File-based storage and comparison
  - `src/main/java/com/example/sf/service/ApexFileStorageService.java`
    - `readByApexId(String apexId)` -> looks for a file starting with `{apexId}_` and returns its contents (Optional<String>).
    - `save(String apexId, String name, String body)` -> writes the file `{apexId}_{safeName}.cls` into the storage directory (creates directory if missing).
  - `src/main/java/com/example/sf/util/ApexDiffUtil.java`
    - `getDiffLines(String oldSource, String newSource)` -> returns a List<String> of differing lines in the format `line N: old='...' new='...'`.

Configuration
- `src/main/resources/application.properties`
  - `storage.apex.path` — directory where Apex files are saved (default: `storage/apex`).
  - Salesforce OAuth / API configuration like `salesforce.clientId`, `salesforce.clientSecret`, `salesforce.authUrl`, `salesforce.tokenUrl`.

Detailed sequence for GET /apex/class/{Id}
1. Caller (browser or curl with session cookie) requests `/apex/class/{Id}`.
2. `ApexController.apexById(id, session)` checks the session for `sf_access_token` and `sf_instance_url`. If missing -> return error instructing user to `/connect`.
3. Calls `SalesforceApexService.getApexClassById(id, token, instance)` which issues a GET to:
   - `{instanceUrl}/services/data/{apiVersion}/tooling/sobjects/ApexClass/{id}`
   - The response JSON contains fields `Id`, `Name`, `Body` (Apex source) and others.
4. Controller parses the JSON and extracts `apexId`, `name`, `body`.
5. Controller calls `ApexFileStorageService.readByApexId(apexId)` to get `existing` (may be null).
6. Controller calls `ApexDiffUtil.getDiffLines(existing, body)` to compute `diffs`.
7. If `diffs` is non-empty, controller calls `ApexFileStorageService.save(apexId, name, body)` to write the new file.
8. Controller returns JSON:
   - `apexId`, `name`, `changed` (boolean), `diffs` (list), `saved` (boolean)

Where the comparison happens
- The comparison occurs in `ApexController` using `ApexDiffUtil.getDiffLines(oldSource, newSource)`.
- Old: file contents read from disk by `ApexFileStorageService.readByApexId(apexId)`.
- New: `Body` from the Tooling API response fetched by `SalesforceApexService`.

Notes & edge-cases
- First fetch: if no file exists yet, `existing` is null and the controller treats it as changed — it will save the new file.
- Comparison is exact line-by-line equality; whitespace differences and line endings are considered changes.
- File storage uses `apexId` in filename, so multiple classes with the same name across orgs will not collide.
- The storage directory is created automatically by `ApexFileStorageService`.

Security considerations
- `src/main/resources/application.properties` contains client secret and credentials in this project — avoid committing secrets into shared repos.
- If you plan to share the codebase with credentials, be aware those will be present in `application.properties` or in the zip you share. Use environment variables or a secrets manager for production.

How to read the saved files on disk
1. Default folder: `storage/apex` relative to project root.
2. File name example: `01pgL000006s0M9QAI_DeveloperEditionUtils.cls` (apexId_name.cls)
3. Use `cat`, `less` or your editor to open the `.cls` file.

Example usage (quick)
1. Start the app:

```
mvn -DskipTests spring-boot:run
```

2. Connect: open `http://localhost:8080/connect` and log in.
3. Get class Ids:

```
curl -s http://localhost:8080/apex/classes
```

4. Compare & save a class:

```
curl -s http://localhost:8080/apex/class/<Id>
```

Optional improvements (future)
- Use Metadata API for fetching full metadata (if you prefer Metadata vs Tooling API) — switch `SalesforceApexService` to call the Metadata endpoint.
- Implement unified diff output (`git diff` style) instead of line-by-line.
- Add versioning (write backups with timestamps, do not overwrite files).
- Add an endpoint `GET /apex/saved` to list stored files and their metadata.

---
File map quick reference
- `src/main/java/com/example/sf/controller/SalesforceController.java` — OAuth connect/callback
- `src/main/java/com/example/sf/controller/ApexController.java` — fetch/compare/save orchestration
- `src/main/java/com/example/sf/service/SalesforceAuthService.java` — token exchange
- `src/main/java/com/example/sf/service/SalesforceApexService.java` — Tooling API calls
- `src/main/java/com/example/sf/service/ApexFileStorageService.java` — read/write local files
- `src/main/java/com/example/sf/util/ApexDiffUtil.java` — comparison algorithm
- `src/main/resources/application.properties` — configuration (storage path + Salesforce credentials)

If you want this document extended (UML/sequence diagram ASCII, sample responses embedded, or a markdown TOC), tell me which format you prefer and I will update the docs.
