package com.example.sf.model;

public class ApexChangeResult {
    private final String apexId;
    private final boolean changed;

    public ApexChangeResult(String apexId, boolean changed) {
        this.apexId = apexId;
        this.changed = changed;
    }

    public String getApexId() { return apexId; }
    public boolean isChanged() { return changed; }
}
