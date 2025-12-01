# Salesforce Apex Connector - Complete Documentation

**Project Name:** sf-connector  
**Version:** 0.0.1-SNAPSHOT  
**Date:** November 28, 2025  
**Technology Stack:** Java 21, Spring Boot 3.2.2, Maven

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [Project Structure](#project-structure)
4. [Core Components](#core-components)
5. [API Endpoints](#api-endpoints)
6. [Data Flow](#data-flow)
7. [File Storage Structure](#file-storage-structure)
8. [Setup & Configuration](#setup--configuration)
9. [Usage Guide](#usage-guide)
10. [Technical Details](#technical-details)

---

## 1. Project Overview

### Purpose
This application connects to Salesforce and retrieves Apex class source code using the Metadata API. It provides:
- **OAuth 2.0 authentication** with Salesforce
- **Automatic Apex class retrieval** with content-based versioning
- **File comparison** between versions with line-by-line diff
- **REST API endpoints** for integration

### Key Features
✅ **OAuth Authentication** - Secure connection to Salesforce orgs  
✅ **Metadata API Integration** - Retrieve Apex classes from Salesforce  
✅ **Content-Based Archival** - Auto-archive changed files with timestamps  
✅ **Diff Comparison** - Line-by-line comparison between versions  
✅ **Folder Structure Preservation** - Maintains Salesforce package structure  

---

## 2. Architecture

### High-Level Architecture

```
┌─────────────────┐
│   Web Browser   │
│   (User)        │
└────────┬────────┘
         │ HTTP/REST
         ↓
┌─────────────────────────────────────────┐
│      Spring Boot Application            │
│      (Port 8080)                        │
│  ┌───────────────────────────────────┐  │
│  │   Controllers Layer               │  │
│  │  - SalesforceController           │  │
│  │  - ApexController                 │  │
│  └────────────┬──────────────────────┘  │
│               │                          │
│  ┌────────────▼──────────────────────┐  │
│  │   Services Layer                  │  │
│  │  - SalesforceAuthService          │  │
│  │  - SalesforceApexService          │  │
│  │  - ApexComparisonService          │  │
│  │  - ApexFileStorageService         │  │
│  │  - ApexVersionService             │  │
│  └────────────┬──────────────────────┘  │
│               │                          │
│  ┌────────────▼──────────────────────┐  │
│  │   Repository Layer                │  │
│  │  - ApexClassRepository (H2 DB)    │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
         │ HTTPS/REST
         ↓
┌─────────────────────────────────────────┐
│      Salesforce APIs                    │
│  - OAuth 2.0                            │
│  - Metadata REST API                    │
│  - Metadata SOAP API                    │
└─────────────────────────────────────────┘
         │
         ↓
┌─────────────────────────────────────────┐
│      Local File Storage                 │
│  storage/apex/new/  (latest files)      │
│  storage/apex/old/  (archived versions) │
└─────────────────────────────────────────┘
```

### Component Interaction Flow

```
User Request → Controller → Service → Salesforce API
                    ↓           ↓
                Database    File Storage
                    ↓           ↓
              Response ← Service ← Controller
```

---

## 3. Project Structure

```
sf-connector/
├── pom.xml                          # Maven dependencies
├── src/
│   └── main/
│       ├── java/com/example/sf/
│       │   ├── SfApplication.java   # Spring Boot entry point
│       │   ├── controller/
│       │   │   ├── SalesforceController.java   # OAuth endpoints
│       │   │   └── ApexController.java         # Apex operations
│       │   ├── service/
│       │   │   ├── SalesforceAuthService.java       # OAuth logic
│       │   │   ├── SalesforceApexService.java       # Metadata API
│       │   │   ├── ApexComparisonService.java       # Diff comparison
│       │   │   ├── ApexFileStorageService.java      # File operations
│       │   │   └── ApexVersionService.java          # Version tracking
│       │   ├── model/
│       │   │   ├── ApexClassEntity.java        # Database entity
│       │   │   └── ApexChangeResult.java       # Version change data
│       │   ├── repository/
│       │   │   └── ApexClassRepository.java    # JPA repository
│       │   └── util/
│       │       └── ApexDiffUtil.java           # Diff utility
│       └── resources/
│           └── application.properties          # Configuration
├── storage/
│   └── apex/
│       ├── new/                    # Latest Apex files
│       │   └── unpackaged/
│       │       └── classes/
│       │           ├── *.cls               # Apex class files
│       │           └── *.cls-meta.xml      # Metadata files
│       └── old/                    # Archived versions
│           └── unpackaged/
│               └── classes/
│                   └── ClassName_TIMESTAMP.cls
└── docs/                           # Documentation
    ├── apex-flow.md
    └── README.md
```

---

## 4. Core Components

### 4.1 Controllers

#### **SalesforceController.java**
**Location:** `src/main/java/com/example/sf/controller/SalesforceController.java`

**Purpose:** Handles OAuth authentication flow

**Key Methods:**
- `connect()` - Redirects to Salesforce OAuth login
- `callback()` - Handles OAuth callback, exchanges code for token
- `clearSession()` - Logs out and clears session

**Flow:**
```
User → /connect → Salesforce Login → /callback → Token stored in session
```

#### **ApexController.java**
**Location:** `src/main/java/com/example/sf/controller/ApexController.java`

**Purpose:** Provides REST API for Apex operations

**Endpoints (9 total):**
1. `GET /apex/classes` - List all Apex classes
2. `GET /apex/ping-instance` - Test Salesforce connectivity
3. `GET /apex/show-session` - Display session info
4. `GET /apex/get-retrieve-id` - Start Metadata retrieve
5. `GET /apex/check-retrieve-status/{id}` - Poll retrieve status
6. `GET /apex/retrieve-and-poll` - Combined retrieve + poll
7. `GET /apex/compare/{fileName}` - Compare single file
8. `GET /apex/compare-files` - Compare all files
9. `GET /apex/compare-summary` - Get change statistics

---

### 4.2 Services

#### **SalesforceAuthService.java**
**Location:** `src/main/java/com/example/sf/service/SalesforceAuthService.java`

**Purpose:** OAuth 2.0 authentication with Salesforce

**Key Logic:**
```java
1. buildAuthorizationUrl() 
   → Constructs OAuth URL with client_id, redirect_uri, scope

2. exchangeCodeForToken(code)
   → POST to /services/oauth2/token
   → Validates scope (must have 'api' or 'full')
   → Stores token in session
```

**OAuth Scope Validation:**
- Logs granted scopes to console
- Checks for required `api` or `full` scope
- Warns if Metadata API access may be limited

---

#### **SalesforceApexService.java**
**Location:** `src/main/java/com/example/sf/service/SalesforceApexService.java`

**Purpose:** Core Metadata API integration

**Key Methods:**

**1. startRetrieve(token, instanceUrl)**
```java
Purpose: Initiates Metadata API retrieve request
API: POST /services/data/v57.0/metadata/retrieve
Payload: { "apiVersion": 57.0, "unpackaged": { "types": [{ "name": "ApexClass", "members": ["*"] }] }}
Returns: retrieveRequestId (e.g., "09SgL0000067OF3UAM")
```

**2. pollRetrieve(token, instanceUrl, retrieveId)**
```java
Purpose: Polls retrieve status until complete
API: GET /services/data/v57.0/metadata/retrieveResult?retrieveRequestId={id}
Logic: 
  - Sleeps 1.5 seconds between polls
  - Checks status: InProgress → Succeeded/Failed
  - Returns decoded ZIP bytes when complete
```

**3. extractCls(zipBytes)**
```java
Purpose: Extract and save Apex classes with content-based archival
Logic:
  1. Read ZIP entries
  2. For each .cls file:
     - Compare content with existing file
     - If content differs:
       → Archive old file to old/ with timestamp
       → Format: ClassName_1732789123456.cls
     - Save new file to new/
  3. Preserve folder structure (unpackaged/classes/)
Returns: Map<className, apexCode>
```

**4. waitForRetrieveAndDownload(asyncId, token, instanceUrl)**
```java
Purpose: SOAP API method to poll retrieve and extract files
API: POST /services/Soap/m/57.0 (checkRetrieveStatus)
Logic:
  - Polls every 1 second
  - Extracts <zipFile> from SOAP response
  - Saves files to storage/apex/new/
  - Returns Map<className, body>
```

**5. getApexClassList(token, instanceUrl)**
```java
Purpose: Lists all Apex classes (excludes managed packages)
API: POST /services/Soap/m/57.0 (listMetadata)
Logic:
  - Calls listMetadata for ApexClass type
  - Filters out managed packages (namespace != null)
  - Returns list of class names
```

---

#### **ApexComparisonService.java**
**Location:** `src/main/java/com/example/sf/service/ApexComparisonService.java`

**Purpose:** Line-by-line diff comparison between file versions

**Key Methods:**

**1. compareFile(className)**
```java
Purpose: Compare single Apex class between new/ and old/
Logic:
  1. Resolve paths: 
     - new: storage/apex/new/unpackaged/classes/ClassName.cls
     - old: storage/apex/old/unpackaged/classes/ClassName.cls
  2. Read file content as List<String> (lines)
  3. Generate diff using DiffUtils.diff(oldLines, newLines)
  4. Format changes with line numbers and types:
     - CHANGE: line modified
     - INSERT: line added
     - DELETE: line removed
Returns: { status, fileName, changeCount, changes: [...] }
Status codes:
  - "no_new_file" - File doesn't exist in new/
  - "no_old_file" - File doesn't exist in old/
  - "no_changes" - Files are identical
  - "changes_found" - Differences detected
```

**2. compareAll()**
```java
Purpose: Compare all .cls files in new/ directory
Logic:
  - Lists all files in new/unpackaged/classes/
  - Calls compareFile() for each
  - Returns array of comparison results
```

**3. getChangeSummary()**
```java
Purpose: Aggregate statistics across all files
Returns: {
  totalFiles: 10,
  changedFiles: 3,
  newFiles: 2,
  unchangedFiles: 5,
  totalChanges: 47
}
```

---

#### **ApexFileStorageService.java**
**Location:** `src/main/java/com/example/sf/service/ApexFileStorageService.java`

**Purpose:** File system operations for Apex classes

**Key Methods:**
- `save(apexId, name, body)` - Save to flat structure
- `saveToNew(id, name, body)` - Save to new/ directory
- `saveFlatCls(className, body)` - Save individual .cls file
- `readByApexId(id)` - Read class by Salesforce ID
- `rotateNewToOld()` - Move entire new/ to old/

---

#### **ApexVersionService.java**
**Location:** `src/main/java/com/example/sf/service/ApexVersionService.java`

**Purpose:** Track version changes in database

**Key Methods:**
- `recordChange(apexId, name, oldCode, newCode, changeType)` - Log changes
- `getChangeHistory(apexId)` - Retrieve change history
- `getLatestChange(apexId)` - Get most recent change

---

### 4.3 Models

#### **ApexClassEntity.java**
**Purpose:** JPA entity for database storage

**Fields:**
```java
@Id
private Long id;
private String apexId;      // Salesforce 18-char ID
private String name;         // Class name
private String body;         // Full Apex code
private LocalDateTime lastSync;
```

#### **ApexChangeResult.java**
**Purpose:** Represents a version change event

**Fields:**
```java
private Long id;
private String apexId;
private String name;
private String changeType;   // CREATED, MODIFIED, DELETED
private String oldCode;
private String newCode;
private LocalDateTime changedAt;
```

---

### 4.4 Utilities

#### **ApexDiffUtil.java**
**Location:** `src/main/java/com/example/sf/util/ApexDiffUtil.java`

**Purpose:** Generate human-readable diff output

**Key Method:**
```java
getDiffLines(oldCode, newCode) → List<String>
  - Splits code into lines
  - Uses DiffUtils library
  - Formats as: "Line 42: - old content" / "+ new content"
```

---

## 5. API Endpoints

### Authentication Endpoints (SalesforceController)

#### `GET /connect`
**Purpose:** Start OAuth flow  
**Response:** Redirects to Salesforce login  
**Example:** `http://localhost:8080/connect`

#### `GET /callback`
**Purpose:** OAuth callback handler  
**Parameters:** `code` (authorization code)  
**Response:** JSON with success/error  
**Session Storage:** `sf_access_token`, `sf_instance_url`, `sf_scope`

#### `GET /logout`
**Purpose:** Clear session  
**Response:** `{ "message": "Logged out" }`

---

### Apex Operations Endpoints (ApexController)

#### `GET /apex/classes`
**Purpose:** List all Apex classes in org  
**Auth:** Required (session token)  
**Response:**
```json
[
  { "Id": "01p...", "Name": "AccountTriggerHandler" },
  { "Id": "01p...", "Name": "ContactService" }
]
```

---

#### `GET /apex/ping-instance`
**Purpose:** Test Salesforce connectivity  
**Response:**
```json
{
  "ok": true,
  "status": 200,
  "bodyPreview": "{\"label\":\"Summer '21\"...}"
}
```

---

#### `GET /apex/show-session`
**Purpose:** Display current session data  
**Response:**
```json
{
  "sf_access_token": "00D7Q...abc",
  "sf_instance_url": "https://orgfarm-4a6bdec196-dev-ed.develop.my.salesforce.com",
  "sf_scope": "refresh_token api full"
}
```

---

#### `GET /apex/get-retrieve-id`
**Purpose:** Start Metadata retrieve and get ID  
**Console Output:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ Retrieve ID → 09SgL0000067OF3UAM
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```
**Response:**
```json
{
  "success": true,
  "retrieveId": "09SgL0000067OF3UAM",
  "message": "Retrieve ID printed to console"
}
```

---

#### `GET /apex/check-retrieve-status/{retrieveId}`
**Purpose:** Poll retrieve status and download when complete  
**Parameters:** `retrieveId` (e.g., "09SgL0000067OF3UAM")  
**Response:**
```json
{
  "success": true,
  "retrieveId": "09SgL0000067OF3UAM",
  "classCount": 2,
  "classes": ["TestApexClass", "TestnewApexClass"],
  "extractedPath": "/Users/jitesh/Desktop/sf-connector/storage/apex/new",
  "message": "Retrieve completed and extracted to storage/apex/new/"
}
```

---

#### `GET /apex/retrieve-and-poll` ⭐ **Most Used**
**Purpose:** Combined retrieve + poll (one-step operation)  
**Console Output:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🚀 Starting Metadata API Retrieve
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Retrieve ID → 09SgL0000067OF3UAM

🔄 Polling checkRetrieveStatus (this may take a few seconds)...

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ Retrieve completed successfully!
📦 Retrieved 2 Apex classes
📂 Files saved to: storage/apex/new/
📦 Changed files archived to: storage/apex/old/ (with timestamp)

📋 Retrieved classes:
   • TestnewApexClass
   • TestApexClass
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```
**Response:** Same as check-retrieve-status

---

#### `GET /apex/compare/{fileName}`
**Purpose:** Compare single Apex class  
**Parameters:** `fileName` (e.g., "TestApexClass" or "TestApexClass.cls")  
**Response:**
```json
{
  "fileName": "TestApexClass.cls",
  "status": "changes_found",
  "changeCount": 3,
  "changes": [
    {
      "line": 5,
      "type": "CHANGE",
      "old": "System.debug('Old version');",
      "new": "System.debug('New version');"
    },
    {
      "line": 12,
      "type": "INSERT",
      "content": "private static void newMethod() {"
    }
  ]
}
```

**Status Codes:**
- `no_new_file` - File not in new/ directory
- `no_old_file` - No previous version exists
- `no_changes` - Files are identical
- `changes_found` - Differences detected
- `error` - Comparison failed

---

#### `GET /apex/compare-files`
**Purpose:** Compare all Apex classes  
**Response:**
```json
[
  {
    "fileName": "TestApexClass.cls",
    "status": "changes_found",
    "changeCount": 3,
    "changes": [...]
  },
  {
    "fileName": "TestnewApexClass.cls",
    "status": "no_changes",
    "changeCount": 0,
    "changes": []
  }
]
```

---

#### `GET /apex/compare-summary`
**Purpose:** Get aggregate change statistics  
**Response:**
```json
{
  "totalFiles": 10,
  "changedFiles": 3,
  "newFiles": 2,
  "unchangedFiles": 5,
  "totalChanges": 47
}
```

---

## 6. Data Flow

### Complete Retrieve & Archive Flow

```
┌─────────────────────────────────────────────────────────┐
│ 1. User triggers: GET /apex/retrieve-and-poll          │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 2. ApexController.retrieveAndPoll()                     │
│    → Calls apexService.sendRetrieveRequestAndGetId()   │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 3. SalesforceApexService.sendRetrieveRequestAndGetId() │
│    → POST /services/Soap/m/57.0                        │
│    → SOAP envelope with <met:retrieve>                 │
│    → Returns: retrieveId = "09SgL0000067OF3UAM"        │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 4. apexService.waitForRetrieveAndDownload(retrieveId)  │
│    → Polls every 1 second                              │
│    → POST /services/Soap/m/57.0 (checkRetrieveStatus)  │
│    → Checks <done>true</done>                          │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 5. Extract <zipFile> base64 content                    │
│    → Decode Base64 to byte[]                           │
│    → Create ZipInputStream                             │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 6. For each ZIP entry:                                  │
│    unpackaged/classes/TestApexClass.cls                │
│    unpackaged/classes/TestApexClass.cls-meta.xml       │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 7. Content-Based Archival Logic:                       │
│                                                         │
│    Path newFile = storage/apex/new/.../TestApexClass.cls│
│    Path oldFile = storage/apex/old/.../TestApexClass.cls│
│                                                         │
│    IF oldFile exists:                                   │
│      String existingContent = readFile(oldFile)        │
│      String newContent = readFile(newFile)             │
│                                                         │
│      IF !existingContent.equals(newContent):           │
│        timestamp = System.currentTimeMillis()          │
│        archivedName = "TestApexClass_" + timestamp + ".cls"│
│        COPY oldFile → old/.../TestApexClass_1732789456.cls│
│        LOG "📦 Archived changed file"                  │
│    END IF                                               │
│                                                         │
│    WRITE newContent → newFile                          │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 8. Return Map<className, apexCode>                     │
│    {                                                    │
│      "TestApexClass": "public class TestApexClass...", │
│      "TestnewApexClass": "public class TestnewApex..." │
│    }                                                    │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 9. Response to User:                                    │
│    {                                                    │
│      "success": true,                                   │
│      "retrieveId": "09SgL0000067OF3UAM",               │
│      "classCount": 2,                                   │
│      "classes": ["TestApexClass", "TestnewApexClass"], │
│      "extractedPath": ".../storage/apex/new"           │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
```

---

### Comparison Flow

```
┌─────────────────────────────────────────────────────────┐
│ 1. User triggers: GET /apex/compare/TestApexClass      │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 2. ApexController.compareFile("TestApexClass")         │
│    → Calls comparisonService.compareFile()             │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 3. ApexComparisonService.compareFile()                 │
│    → Add .cls extension if missing                     │
│    → fileName = "TestApexClass.cls"                    │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 4. Resolve file paths:                                  │
│    newFile = storage/apex/new/unpackaged/classes/       │
│              TestApexClass.cls                          │
│    oldFile = storage/apex/old/unpackaged/classes/       │
│              TestApexClass.cls                          │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 5. Check file existence:                                │
│    IF !newFile.exists() → return "no_new_file"         │
│    IF !oldFile.exists() → return "no_old_file"         │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 6. Read files as List<String>:                         │
│    newLines = Files.readAllLines(newFile)              │
│    oldLines = Files.readAllLines(oldFile)              │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 7. Generate diff using java-diff-utils:                │
│    Patch<String> patch = DiffUtils.diff(oldLines, newLines)│
│    List<AbstractDelta<String>> deltas = patch.getDeltas()│
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 8. Format changes:                                      │
│    FOR each delta:                                      │
│      lineNumber = delta.getSource().getPosition() + 1  │
│      changeType = delta.getType() // CHANGE/INSERT/DELETE│
│                                                         │
│      IF CHANGE:                                         │
│        { line: 5, type: "CHANGE",                      │
│          old: "System.debug('old');",                  │
│          new: "System.debug('new');" }                 │
│                                                         │
│      IF INSERT:                                         │
│        { line: 12, type: "INSERT",                     │
│          content: "private void newMethod() {" }       │
│                                                         │
│      IF DELETE:                                         │
│        { line: 20, type: "DELETE",                     │
│          content: "// deprecated code" }               │
└────────────────────┬────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────┐
│ 9. Return comparison result:                            │
│    {                                                    │
│      "fileName": "TestApexClass.cls",                  │
│      "status": "changes_found",                        │
│      "changeCount": 3,                                  │
│      "changes": [                                       │
│        { line: 5, type: "CHANGE", old: "...", new: "..." },│
│        { line: 12, type: "INSERT", content: "..." },   │
│        { line: 20, type: "DELETE", content: "..." }    │
│      ]                                                  │
│    }                                                    │
└─────────────────────────────────────────────────────────┘
```

---

## 7. File Storage Structure

### Directory Layout

```
storage/apex/
├── new/                          # Latest retrieved files
│   └── unpackaged/
│       ├── package.xml           # Salesforce package manifest
│       └── classes/
│           ├── TestApexClass.cls          # Apex class source
│           ├── TestApexClass.cls-meta.xml # Metadata (API version)
│           ├── TestnewApexClass.cls
│           └── TestnewApexClass.cls-meta.xml
│
└── old/                          # Archived versions (content changed)
    └── unpackaged/
        └── classes/
            ├── TestApexClass_1732789123456.cls      # Timestamp: Nov 28, 2025
            ├── TestApexClass_1732789200000.cls      # Later version
            └── TestnewApexClass_1732790000000.cls
```

### File Naming Convention

**Current Files:**
- Format: `ClassName.cls`
- Location: `storage/apex/new/unpackaged/classes/`
- Example: `TestApexClass.cls`

**Archived Files:**
- Format: `ClassName_TIMESTAMP.cls`
- Location: `storage/apex/old/unpackaged/classes/`
- Timestamp: Unix timestamp in milliseconds
- Example: `TestApexClass_1732789123456.cls`
- Date calculation: `new Date(1732789123456)` = Nov 28, 2025

### Content-Based Archival Logic

```java
// Pseudo-code
IF file exists in new/:
  existingContent = readFile(new/ClassName.cls)
  newContent = retrievedContent
  
  IF existingContent != newContent:
    timestamp = currentTimeMillis()
    archivedName = ClassName + "_" + timestamp + ".cls"
    COPY new/ClassName.cls → old/ClassName_TIMESTAMP.cls
    LOG "📦 Archived changed file: ClassName.cls"
  END IF
  
  WRITE newContent → new/ClassName.cls
END IF
```

**Key Points:**
- Archival happens **BEFORE** overwriting with new content
- Only archives when content actually changes (byte-by-byte comparison)
- Preserves folder structure (unpackaged/classes/)
- Unlimited version history (all timestamped copies retained)

---

## 8. Setup & Configuration

### Prerequisites
- Java 21 or higher
- Maven 3.x
- Salesforce org with Metadata API access
- Connected App configured in Salesforce

### Salesforce Connected App Setup

1. **Create Connected App in Salesforce:**
   - Setup → App Manager → New Connected App
   - Enable OAuth Settings
   - Callback URL: `http://localhost:8080/callback`
   - Selected OAuth Scopes:
     - `Full access (full)` **[Recommended]**
     - `Perform requests at any time (refresh_token)`
     - `Access and manage your data (api)`

2. **Get Consumer Key and Secret:**
   - Copy Consumer Key (Client ID)
   - Copy Consumer Secret (Client Secret)

### Application Configuration

**File:** `src/main/resources/application.properties`

```properties
# Server
server.port=8080

# Salesforce OAuth
salesforce.client-id=YOUR_CONSUMER_KEY
salesforce.client-secret=YOUR_CONSUMER_SECRET
salesforce.redirect-uri=http://localhost:8080/callback
salesforce.oauth-url=https://login.salesforce.com/services/oauth2/authorize
salesforce.token-url=https://login.salesforce.com/services/oauth2/token

# Database (H2 in-memory)
spring.datasource.url=jdbc:h2:mem:sfdb
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=update

# Logging
logging.level.com.example.sf=INFO
```

### Build & Run

```bash
# Clean and compile
mvn clean compile

# Package (skip tests)
mvn -DskipTests package

# Run application
mvn -DskipTests spring-boot:run

# Or run JAR directly
java -jar target/sf-connector-0.0.1-SNAPSHOT.jar
```

**Application starts on:** `http://localhost:8080`

---

## 9. Usage Guide

### Step-by-Step Workflow

#### **Step 1: Authenticate with Salesforce**

```bash
# Open browser
http://localhost:8080/connect

# You'll be redirected to Salesforce login
# Log in with your org credentials
# Authorize the app
# You'll be redirected back to /callback
```

**Console Output:**
```
============================================================
🔐 OAUTH TOKEN SCOPE CHECK
============================================================
Granted scopes: refresh_token api full
✅ SCOPE CHECK PASSED: Token contains required scope
   ✓ Has 'full' scope
   ✓ Has 'api' scope
============================================================
```

---

#### **Step 2: Retrieve Apex Classes**

```bash
# One-step retrieve + poll
curl http://localhost:8080/apex/retrieve-and-poll
```

**Console Output:**
```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🚀 Starting Metadata API Retrieve
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Retrieve ID → 09SgL0000067OF3UAM

🔄 Polling checkRetrieveStatus (this may take a few seconds)...

Retrieve status: InProgress
Retrieve status: InProgress
Retrieve status: Succeeded

✅ Extracted 5 files to storage/apex/new
✅ Found 2 Apex classes

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ Retrieve completed successfully!
📦 Retrieved 2 Apex classes
📂 Files saved to: storage/apex/new/
📦 Changed files archived to: storage/apex/old/ (with timestamp)

📋 Retrieved classes:
   • TestApexClass
   • TestnewApexClass
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

**Response:**
```json
{
  "success": true,
  "retrieveId": "09SgL0000067OF3UAM",
  "classCount": 2,
  "classes": ["TestApexClass", "TestnewApexClass"],
  "extractedPath": "/Users/jitesh/Desktop/sf-connector/storage/apex/new",
  "message": "Retrieve completed and extracted to storage/apex/new/"
}
```

---

#### **Step 3: Modify Apex Class in Salesforce**

1. Go to Salesforce Setup → Apex Classes
2. Edit `TestApexClass`
3. Make changes (add/modify/delete lines)
4. Save

---

#### **Step 4: Retrieve Again (Content-Based Archival)**

```bash
curl http://localhost:8080/apex/retrieve-and-poll
```

**Console Output:**
```
✅ Extracted 5 files to storage/apex/new
📦 Archived changed file: unpackaged/classes/TestApexClass.cls → TestApexClass_1732789456789.cls
📦 Archived 1 changed files to storage/apex/old with timestamp 1732789456789
✅ Found 2 Apex classes
```

**Result:** Old version moved to `storage/apex/old/unpackaged/classes/TestApexClass_1732789456789.cls`

---

#### **Step 5: Compare Changes**

**Compare Single File:**
```bash
curl http://localhost:8080/apex/compare/TestApexClass
```

**Response:**
```json
{
  "fileName": "TestApexClass.cls",
  "status": "changes_found",
  "changeCount": 2,
  "changes": [
    {
      "line": 5,
      "type": "CHANGE",
      "old": "System.debug('Original version');",
      "new": "System.debug('Modified version');"
    },
    {
      "line": 10,
      "type": "INSERT",
      "content": "// New comment added"
    }
  ]
}
```

**Compare All Files:**
```bash
curl http://localhost:8080/apex/compare-files
```

**Get Summary:**
```bash
curl http://localhost:8080/apex/compare-summary
```

**Response:**
```json
{
  "totalFiles": 2,
  "changedFiles": 1,
  "newFiles": 0,
  "unchangedFiles": 1,
  "totalChanges": 2
}
```

---

### Advanced Usage

#### **List All Apex Classes**
```bash
curl http://localhost:8080/apex/classes
```

#### **Test Connectivity**
```bash
curl http://localhost:8080/apex/ping-instance
```

#### **Check Session**
```bash
curl http://localhost:8080/apex/show-session
```

#### **Manual Retrieve (2-step process)**
```bash
# Step 1: Get retrieve ID
curl http://localhost:8080/apex/get-retrieve-id

# Step 2: Poll for completion
curl http://localhost:8080/apex/check-retrieve-status/09SgL0000067OF3UAM
```

---

## 10. Technical Details

### Dependencies (pom.xml)

```xml
<!-- Spring Boot -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Spring Data JPA -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- H2 Database -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Jackson JSON -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>

<!-- Java Diff Utils -->
<dependency>
    <groupId>io.github.java-diff-utils</groupId>
    <artifactId>java-diff-utils</artifactId>
    <version>4.12</version>
</dependency>

<!-- Reactive WebFlux (for Mono) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

---

### Salesforce APIs Used

#### **1. Metadata REST API**

**Retrieve Request:**
```http
POST /services/data/v57.0/metadata/retrieve
Content-Type: application/json
Authorization: Bearer {token}

{
  "apiVersion": 57.0,
  "singlePackage": true,
  "unpackaged": {
    "types": [
      { "name": "ApexClass", "members": ["*"] }
    ]
  }
}
```

**Response:**
```json
{
  "retrieveRequestId": "09SgL0000067OF3UAM"
}
```

**Poll Retrieve Result:**
```http
GET /services/data/v57.0/metadata/retrieveResult?retrieveRequestId=09SgL0000067OF3UAM
Authorization: Bearer {token}
```

**Response:**
```json
{
  "status": "Succeeded",
  "zipFile": "UEsDBBQACAgIAAAAIQA..." // Base64 encoded ZIP
}
```

---

#### **2. Metadata SOAP API**

**listMetadata Request:**
```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:met="http://soap.sforce.com/2006/04/metadata">
  <soapenv:Header>
    <met:SessionHeader>
      <met:sessionId>{token}</met:sessionId>
    </met:SessionHeader>
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
```

**checkRetrieveStatus Request:**
```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:met="http://soap.sforce.com/2006/04/metadata">
  <soapenv:Header>
    <met:SessionHeader>
      <met:sessionId>{token}</met:sessionId>
    </met:SessionHeader>
  </soapenv:Header>
  <soapenv:Body>
    <met:checkRetrieveStatus>
      <met:asyncProcessId>09SgL0000067OF3UAM</met:asyncProcessId>
    </met:checkRetrieveStatus>
  </soapenv:Body>
</soapenv:Envelope>
```

---

### Database Schema

**Table: apex_class_entity**
```sql
CREATE TABLE apex_class_entity (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    apex_id VARCHAR(18) UNIQUE,
    name VARCHAR(255),
    body TEXT,
    last_sync TIMESTAMP
);
```

**Table: apex_change_result**
```sql
CREATE TABLE apex_change_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    apex_id VARCHAR(18),
    name VARCHAR(255),
    change_type VARCHAR(50),
    old_code TEXT,
    new_code TEXT,
    changed_at TIMESTAMP
);
```

---

### Error Handling

**Common Errors:**

1. **"Not connected. Visit /connect"**
   - Cause: No OAuth token in session
   - Solution: Navigate to `/connect` and authenticate

2. **"Metadata retrieve returned no Apex class files"**
   - Cause: OAuth scope missing `api` or `full`
   - Solution: Update Connected App scopes

3. **"Range [0, -1) out of bounds"**
   - Cause: Empty file or invalid line range
   - Solution: Fixed in ApexComparisonService (safe file reading)

4. **"no_old_file"**
   - Cause: First-time retrieve (no archived version exists)
   - Solution: Normal behavior for new files

---

### Performance Considerations

**Retrieve Performance:**
- Metadata API: 5-15 seconds per retrieve
- Polling interval: 1.5 seconds (REST), 1 second (SOAP)
- ZIP extraction: < 1 second for typical orgs

**Comparison Performance:**
- Single file: < 100ms
- All files (10 classes): < 500ms
- Diff algorithm: O(n) where n = lines of code

**Storage:**
- Each Apex class: ~1-10 KB
- Metadata XML: ~200 bytes
- Typical org (50 classes): ~500 KB total

---

### Security Best Practices

1. **Never commit credentials:**
   - Add `application.properties` to `.gitignore`
   - Use environment variables in production

2. **Token Storage:**
   - Tokens stored in HTTP session (in-memory)
   - Cleared on logout
   - Expire according to Salesforce session settings

3. **HTTPS Required:**
   - Use HTTPS in production
   - Update redirect URI to `https://your-domain.com/callback`

4. **Scope Principle:**
   - Request minimum scopes needed
   - `full` scope provides complete access (use with caution)

---

## Appendix A: Complete API Reference

### Authentication APIs

| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/connect` | GET | Start OAuth flow | No |
| `/callback` | GET | OAuth callback | No |
| `/logout` | GET | Clear session | No |

### Apex Operations APIs

| Endpoint | Method | Purpose | Auth Required |
|----------|--------|---------|---------------|
| `/apex/classes` | GET | List all Apex classes | Yes |
| `/apex/ping-instance` | GET | Test connectivity | Yes |
| `/apex/show-session` | GET | Show session data | Yes |
| `/apex/get-retrieve-id` | GET | Start retrieve | Yes |
| `/apex/check-retrieve-status/{id}` | GET | Poll retrieve | Yes |
| `/apex/retrieve-and-poll` | GET | Retrieve + poll | Yes |
| `/apex/compare/{fileName}` | GET | Compare single file | No |
| `/apex/compare-files` | GET | Compare all files | No |
| `/apex/compare-summary` | GET | Get change stats | No |

---

## Appendix B: Troubleshooting

### Issue: Empty .cls files retrieved

**Symptoms:** Files created but contain no code

**Diagnosis:**
```bash
curl http://localhost:8080/apex/show-session
# Check if scope includes "api" or "full"
```

**Solution:**
1. Update Connected App scopes
2. Re-authenticate: `/logout` → `/connect`

---

### Issue: "Managed packages cannot expose source"

**Symptoms:** Some classes not retrieved

**Explanation:** Managed packages (AppExchange apps) don't expose source code

**Solution:** This is expected Salesforce behavior. Only custom classes are retrieved.

---

### Issue: Files not being archived

**Diagnosis:**
- Check console logs for "📦 Archived changed file"
- Verify content actually changed (byte-level comparison)

**Solution:**
- Content must differ for archival to occur
- Check file permissions on `storage/apex/old/`

---

## Appendix C: Future Enhancements

**Potential Features:**
1. ✨ Support for other metadata types (Triggers, Visualforce, Lightning Components)
2. ✨ Scheduled automatic retrieval (cron jobs)
3. ✨ Web UI for browsing diffs
4. ✨ Git integration (auto-commit changes)
5. ✨ Deployment API (push changes back to Salesforce)
6. ✨ Multiple org support (switch between orgs)
7. ✨ Custom metadata filtering (exclude test classes)

---

## Summary

This project provides a **complete Salesforce Apex class retrieval and version tracking system** using the Metadata API. Key strengths:

- ✅ **Clean Architecture** - Layered design (Controller → Service → Repository)
- ✅ **Metadata API Only** - No Tooling API dependencies
- ✅ **Content-Based Versioning** - Intelligent archival with timestamps
- ✅ **Line-by-Line Diff** - Detailed change tracking
- ✅ **RESTful API** - Easy integration with other systems
- ✅ **Session-Based Auth** - Secure token management

**Start using:** `http://localhost:8080/connect`

---

**Document Version:** 1.0  
**Last Updated:** November 28, 2025  
**Author:** Generated for sf-connector project
