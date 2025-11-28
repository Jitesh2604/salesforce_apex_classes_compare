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
        Path oldFile = oldClassesDir.resolve(fileName);

        // Check if new file exists
        if (!Files.exists(newFile)) {
            result.put("status", "no_new_file");
            result.put("message", "File not found in new/");
            result.put("changes", Collections.emptyList());
            return result;
        }

        // Check if old file exists
        if (!Files.exists(oldFile)) {
            result.put("status", "no_old_file");
            result.put("message", "No previous version found in old/");
            result.put("changes", Collections.emptyList());
            return result;
        }

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
