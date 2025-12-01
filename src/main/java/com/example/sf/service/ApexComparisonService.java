package com.example.sf.service;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ApexComparisonService {

    private static final Logger LOG = LoggerFactory.getLogger(ApexComparisonService.class);

    private final Path newClassesDir = Path.of("storage", "apex", "new", "unpackaged", "classes");
    private final Path oldClassesDir = Path.of("storage", "apex", "old", "unpackaged", "classes");

    /**
     * Find the latest timestamped archived file for a given class name.
     * Pattern: ClassName_TIMESTAMP.cls
     * Returns the file with the highest (most recent) timestamp.
     */
    private Path findLatestArchivedFile(String className) {
        try {
            if (!Files.exists(oldClassesDir)) {
                return null;
            }

            String baseClassName = className.replace(".cls", "");
            String pattern = baseClassName + "_";

            // Find all files matching ClassName_*.cls
            try (Stream<Path> paths = Files.list(oldClassesDir)) {
                Optional<Path> latestFile = paths
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(pattern) && name.endsWith(".cls");
                    })
                    .max(Comparator.comparing(p -> {
                        // Extract timestamp from filename: ClassName_TIMESTAMP.cls
                        String name = p.getFileName().toString();
                        try {
                            int start = name.lastIndexOf('_') + 1;
                            int end = name.lastIndexOf('.');
                            if (start > 0 && end > start) {
                                return Long.parseLong(name.substring(start, end));
                            }
                        } catch (Exception e) {
                            LOG.warn("Unable to parse timestamp from filename: {}", name);
                        }
                        return 0L;
                    }));

                if (latestFile.isPresent()) {
                    LOG.info("Found archived file for comparison: {}", latestFile.get().getFileName());
                }

                return latestFile.orElse(null);
            }
        } catch (Exception e) {
            LOG.error("Error finding archived file for {}: {}", className, e.getMessage());
            return null;
        }
    }

    /**
     * Compare a single file between new/ and old/ folders.
     * Input: class name without .cls extension
     * Returns detailed line-by-line changes.
     */
    public Map<String, Object> compareFile(String className) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        
        // Add .cls extension if not present
        String fileName = className.endsWith(".cls") ? className : className + ".cls";
        result.put("fileName", fileName);

        // Build paths
        Path newFile = newClassesDir.resolve(fileName);

        // Check if new file exists
        if (!Files.exists(newFile)) {
            result.put("status", "no_new_file");
            result.put("message", "File not found in new/");
            result.put("changes", Collections.emptyList());
            return result;
        }

        // Find the latest timestamped version in old/ directory
        // Pattern: ClassName_TIMESTAMP.cls (e.g., TestApexClass_1764354995352.cls)
        Path oldFile = findLatestArchivedFile(className);

        // Read new file content (always include in response)
        String latestCode = Files.readString(newFile);
        
        // Check if old file exists
        if (oldFile == null || !Files.exists(oldFile)) {
            result.put("status", "no_old_file");
            result.put("message", "No previous version found in old/");
            result.put("changeCount", 0);
            result.put("changes", Collections.emptyList());
            result.put("new", latestCode);
            return result;
        }
        
        result.put("oldFile", oldFile.getFileName().toString()); // Show which archived file is being compared

        // Read file contents safely
        List<String> newLines = Files.readAllLines(newFile);
        List<String> oldLines = Files.readAllLines(oldFile);

        // Handle empty files
        if (newLines == null) newLines = Collections.emptyList();
        if (oldLines == null) oldLines = Collections.emptyList();

        // Generate diff
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<AbstractDelta<String>> deltas = patch.getDeltas();

        if (deltas.isEmpty()) {
            result.put("status", "no_changes");
            result.put("message", "Files are identical");
            result.put("changeCount", 0);
            result.put("changes", Collections.emptyList());
            result.put("new", latestCode);
            return result;
        }

        // Format changes
        List<Map<String, Object>> changes = new ArrayList<>();
        for (AbstractDelta<String> delta : deltas) {
            Map<String, Object> change = new LinkedHashMap<>();
            
            int lineNumber = delta.getSource().getPosition() + 1; // 1-based line number
            change.put("line", lineNumber);
            
            String changeType = delta.getType().toString(); // CHANGE, INSERT, DELETE
            change.put("type", changeType);
            
            List<String> oldContent = delta.getSource().getLines();
            List<String> newContent = delta.getTarget().getLines();
            
            // Add content based on change type
            if (changeType.equals("CHANGE")) {
                if (!oldContent.isEmpty()) {
                    change.put("old", String.join("\n", oldContent));
                }
                if (!newContent.isEmpty()) {
                    change.put("new", String.join("\n", newContent));
                }
            } else if (changeType.equals("INSERT")) {
                change.put("content", String.join("\n", newContent));
            } else if (changeType.equals("DELETE")) {
                change.put("content", String.join("\n", oldContent));
            }
            
            changes.add(change);
        }

        result.put("status", "changes_found");
        result.put("changeCount", changes.size());
        result.put("changes", changes);
        // result.put("new", latestCode);

        return result;
    }

    /**
     * Compare all .cls files between new/ and old/ folders.
     * Returns an array of comparison results for each file.
     */
    public List<Map<String, Object>> compareAll() throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();

        if (!Files.exists(newClassesDir)) {
            LOG.warn("new/unpackaged/classes/ directory does not exist");
            return results;
        }

        // Find all .cls files in new/
        try (Stream<Path> paths = Files.list(newClassesDir)) {
            List<Path> newFiles = paths
                .filter(p -> p.toString().endsWith(".cls"))
                .collect(Collectors.toList());

            for (Path newFile : newFiles) {
                String fileName = newFile.getFileName().toString();
                String className = fileName.replace(".cls", "");
                
                try {
                    Map<String, Object> comparison = compareFile(className);
                    results.add(comparison);
                } catch (Exception e) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("fileName", fileName);
                    error.put("status", "error");
                    error.put("message", "Comparison failed: " + e.getMessage());
                    error.put("changes", Collections.emptyList());
                    results.add(error);
                }
            }
        }

        return results;
    }

    /**
     * Get summary statistics of changes across all files.
     */
    public Map<String, Object> getChangeSummary() throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        
        if (!Files.exists(newClassesDir)) {
            summary.put("totalFiles", 0);
            summary.put("changedFiles", 0);
            summary.put("newFiles", 0);
            summary.put("unchangedFiles", 0);
            summary.put("totalChanges", 0);
            return summary;
        }

        List<Map<String, Object>> allComparisons = compareAll();
        
        int totalFiles = allComparisons.size();
        int changedFiles = 0;
        int newFiles = 0;
        int unchangedFiles = 0;
        int totalChanges = 0;

        for (Map<String, Object> comparison : allComparisons) {
            String status = (String) comparison.get("status");
            if ("changes_found".equals(status)) {
                changedFiles++;
                Object changeCountObj = comparison.get("changeCount");
                if (changeCountObj instanceof Integer) {
                    totalChanges += (Integer) changeCountObj;
                }
            } else if ("no_old_file".equals(status)) {
                newFiles++;
            } else if ("no_changes".equals(status)) {
                unchangedFiles++;
            }
        }

        summary.put("totalFiles", totalFiles);
        summary.put("changedFiles", changedFiles);
        summary.put("newFiles", newFiles);
        summary.put("unchangedFiles", unchangedFiles);
        summary.put("totalChanges", totalChanges);

        return summary;
    }
}
