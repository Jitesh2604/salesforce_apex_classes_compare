package com.example.sf.service;

import org.springframework.stereotype.Service;

@Service
public class ApexVersionService {

    // Simple version compare utility: returns true if newSource differs from storedSource
    public boolean hasChanged(String storedSource, String newSource) {
        if (storedSource == null && newSource == null) return false;
        if (storedSource == null) return true;
        if (newSource == null) return true;
        return !storedSource.equals(newSource);
    }
}
