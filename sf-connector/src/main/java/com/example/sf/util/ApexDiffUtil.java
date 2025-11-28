package com.example.sf.util;

import java.util.ArrayList;
import java.util.List;

public class ApexDiffUtil {

    /**
     * Improved diff utility:
     *  - Normalizes whitespace
     *  - Adds readable line-by-line comparison
     *  - Handles long single-line Apex from Metadata API
     *  - Does NOT hide large lines (no "(hidden)")
     */
    public static List<String> getDiffLines(String oldSource, String newSource) {

        // normalize nulls
        if (oldSource == null) oldSource = "";
        if (newSource == null) newSource = "";

        // Normalize line endings
        oldSource = normalizeApex(oldSource);
        newSource = normalizeApex(newSource);

        String[] oldLines = oldSource.split("\\r?\\n");
        String[] newLines = newSource.split("\\r?\\n");

        List<String> diffs = new ArrayList<>();
        int max = Math.max(oldLines.length, newLines.length);

        for (int i = 0; i < max; i++) {
            String o = (i < oldLines.length) ? oldLines[i] : "";
            String n = (i < newLines.length) ? newLines[i] : "";

            // Ignore whitespace-only differences
            if (o.trim().equals(n.trim())) {
                continue;
            }

            if (!o.equals(n)) {
                diffs.add(
                        "line " + (i + 1) + ":\n" +
                                "  old: \"" + o + "\"\n" +
                                "  new: \"" + n + "\""
                );
            }
        }

        return diffs;
    }

    /**
     * Attempts to pretty-normalize Apex code for better diffing.
     * Adds line breaks around braces and semicolons where needed.
     */
    private static String normalizeApex(String src) {
        if (src == null) return "";

        // Fix Salesforce Metadata returning one huge line
        src = src.replace("{", "{\n")
                 .replace("}", "}\n")
                 .replace(";", ";\n");

        // Remove double-newlines
        src = src.replaceAll("\n{2,}", "\n");

        return src.trim();
    }
}
