package com.example.sf.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ApexFileStorageService {

    private final Path storagePath;
    private final Path newPath;
    private final Path oldPath;
    private static final Logger LOG = LoggerFactory.getLogger(ApexFileStorageService.class);

    public ApexFileStorageService(@Value("${storage.apex.path:storage/apex}") String storageDir) throws IOException {
        this.storagePath = Path.of(storageDir);
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
        }
        this.newPath = storagePath.resolve("new");
        this.oldPath = storagePath.resolve("old");
        if (!Files.exists(newPath)) Files.createDirectories(newPath);
        if (!Files.exists(oldPath)) Files.createDirectories(oldPath);
    }

    /**
     * Read stored apex class body by apexId (file named {apexId}_{name}.cls)
     */
    public Optional<String> readByApexId(String apexId) {
        try (var stream = Files.list(storagePath)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith(apexId + "_") && p.toString().endsWith(".cls"))
                .findFirst()
                .map(path -> {
                    try {
                        return Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        LOG.warn("Failed to read apex file {}: {}", path.getFileName(), ex.getMessage(), ex);
                        return null;
                    }
                });
        } catch (IOException e) {
            LOG.warn("Error finding file for apexId={}: {}", apexId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Save apex class file. Always saves.  
     * File name: {apexId}_{safeName}.cls  
     * - Makes backup only if content actually changed  
     * - Deletes old file if name changed (different safeName)
     */
    public boolean save(String apexId, String name, String body) {
        try {
            String safeName = (name == null ? "unknown" :
                    name.replaceAll("[^a-zA-Z0-9_\\-]", "_"));

            String fileName = apexId + "_" + safeName + ".cls";
            Path target = storagePath.resolve(fileName);

            String newContent = (body == null ? "" : body);

            // ✨ Delete old file if name changed
            deleteOldNameFiles(apexId, fileName);

            // 📄 If file exists, check if content changed before backup
            if (Files.exists(target)) {
                String existing = Files.readString(target, StandardCharsets.UTF_8);
                if (!existing.equals(newContent)) {
                    Path bak = storagePath.resolve(fileName + ".bak");
                    Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                    LOG.info("Created backup {} ({} bytes)", bak.getFileName(), Files.size(bak));
                }
            }

            // 📝 Always write new content
            Files.writeString(target, newContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            LOG.info("Saved file {} ({} bytes)", fileName, Files.size(target));
            return true;

        } catch (Exception e) {
            LOG.error("Failed to save apex file for id={} name={}: {}", apexId, name, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Save class into the `new` folder instead of root storage (used for retrieve+compare flows).
     */
    public boolean saveToNew(String apexId, String name, String body) {
        try {
            String safeName = (name == null ? "unknown" : name.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
            String fileName = apexId + "_" + safeName + ".cls";
            Path target = newPath.resolve(fileName);

            String newContent = (body == null ? "" : body);

            // ensure directory
            if (!Files.exists(newPath)) Files.createDirectories(newPath);

            Files.writeString(target, newContent, java.nio.charset.StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            LOG.info("Saved new file {} ({} bytes)", fileName, Files.size(target));
            return true;
        } catch (Exception e) {
            LOG.error("Failed to save new apex file for id={} name={}: {}", apexId, name, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Read class content from the `old` folder by apexId.
     */
    public Optional<String> readOldByApexId(String apexId) {
        try (var stream = Files.list(oldPath)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(apexId + "_") && p.toString().endsWith(".cls"))
                    .findFirst()
                    .map(path -> {
                        try {
                            return Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                        } catch (IOException ex) {
                            LOG.warn("Failed to read old apex file {}: {}", path.getFileName(), ex.getMessage(), ex);
                            return null;
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Error finding old file for apexId={}: {}", apexId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Save class file directly to storage/apex/{ClassName}.cls (flat structure, no subdirs).
     * Used by the simplified retrieve-all flow.
     */
    public boolean saveFlatCls(String className, String body) {
        try {
            String safeName = (className == null ? "unknown" : className.replaceAll("[^a-zA-Z0-9_\\-]", "_"));
            String fileName = safeName + ".cls";
            Path target = storagePath.resolve(fileName);

            String newContent = (body == null ? "" : body);

            Files.writeString(target, newContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            LOG.info("Saved flat file {} ({} bytes)", fileName, Files.size(target));
            return true;
        } catch (Exception e) {
            LOG.error("Failed to save flat cls file for class={}: {}", className, e.getMessage(), e);
            return false;
        }
    }

    /**
     * After comparison, rotate `new` into `old` (delete old contents and move new files into old).
     */
    public void rotateNewToOld() {
        try {
            // delete old files
            if (Files.exists(oldPath)) {
                try (var s = Files.list(oldPath)) {
                    s.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            } else {
                Files.createDirectories(oldPath);
            }

            // move new files to old
            if (Files.exists(newPath)) {
                try (var s = Files.list(newPath)) {
                    s.forEach(p -> {
                        try {
                            Path dest = oldPath.resolve(p.getFileName());
                            Files.move(p, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception ignored) {}
                    });
                }
            }

            // ensure new dir exists empty
            if (Files.exists(newPath)) {
                // already moved files out; ensure directory empty
            } else {
                Files.createDirectories(newPath);
            }
        } catch (Exception e) {
            LOG.warn("rotateNewToOld failed: {}", e.getMessage(), e);
        }
    }

    /**
     * If the class name changed (e.g. DeveloperEditionUtils → DeveloperEditionUtilsTest),
     * remove old file to avoid multiple old versions being stored.
     */
    private void deleteOldNameFiles(String apexId, String expectedFileName) {
        try (var stream = Files.list(storagePath)) {
            stream.filter(p ->
                    p.getFileName().toString().startsWith(apexId + "_") &&
                    !p.getFileName().toString().equals(expectedFileName) &&
                    p.getFileName().toString().endsWith(".cls")
            ).forEach(old -> {
                try {
                    Files.deleteIfExists(old);
                    LOG.info("Removed old class file {}", old.getFileName());
                } catch (IOException ex) {
                    LOG.warn("Failed to delete old file {}: {}", old, ex.getMessage());
                }
            });
        } catch (IOException ignored) {}
    }

    /**
     * Expose root storage directory so other services can create subfolders (e.g. storage/apex/new).
     */
    public Path getStorageRoot() {
        return storagePath;
    }

    /**
     * Read the apiVersion from the first .cls-meta.xml file in storage/apex/new/
     * Returns null if no files exist or apiVersion cannot be determined.
     */
    public String readApiVersionFromNew() {
        try {
            Path newDir = storagePath.resolve("new");
            if (!Files.exists(newDir)) return null;
            
            // Find first .cls-meta.xml file recursively
            Optional<Path> metaFile = Files.walk(newDir)
                .filter(p -> p.toString().endsWith(".cls-meta.xml"))
                .findFirst();
            
            if (metaFile.isPresent()) {
                String content = Files.readString(metaFile.get(), StandardCharsets.UTF_8);
                // Extract apiVersion using simple regex
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<apiVersion>(.*?)</apiVersion>");
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read apiVersion from new/: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Move all files from storage/apex/new/ to storage/apex/old/
     * This archives the previous version before retrieving new classes.
     */
    public void archiveNewToOld() {
        try {
            Path newDir = storagePath.resolve("new");
            Path oldDir = storagePath.resolve("old");
            
            // Ensure old directory exists
            if (!Files.exists(oldDir)) {
                Files.createDirectories(oldDir);
            }
            
            // Delete all existing files in old/
            if (Files.exists(oldDir)) {
                Files.walk(oldDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {}
                    });
                // Recreate old directory
                Files.createDirectories(oldDir);
            }
            
            // Move all files from new/ to old/ recursively
            if (Files.exists(newDir)) {
                Files.walk(newDir)
                    .filter(p -> !p.equals(newDir)) // Don't move the directory itself
                    .sorted(java.util.Comparator.naturalOrder())
                    .forEach(source -> {
                        try {
                            Path relative = newDir.relativize(source);
                            Path target = oldDir.resolve(relative);
                            
                            if (Files.isDirectory(source)) {
                                Files.createDirectories(target);
                            } else {
                                // Ensure parent directory exists
                                Path parent = target.getParent();
                                if (parent != null && !Files.exists(parent)) {
                                    Files.createDirectories(parent);
                                }
                                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception e) {
                            LOG.warn("Failed to move {}: {}", source, e.getMessage());
                        }
                    });
                
                // Clean up empty directories in new/
                Files.walk(newDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(newDir))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {}
                    });
                
                // Recreate new directory
                Files.createDirectories(newDir);
                
                LOG.info("✅ Archived all files from new/ to old/");
            }
        } catch (Exception e) {
            LOG.error("Failed to archive new/ to old/: {}", e.getMessage(), e);
        }
    }
}
