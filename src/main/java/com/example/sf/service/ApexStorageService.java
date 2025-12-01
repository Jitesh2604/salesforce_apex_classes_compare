package com.example.sf.service;

import com.example.sf.model.ApexClassEntity;
import com.example.sf.repository.ApexClassRepository;
import org.springframework.stereotype.Service;

@Service
public class ApexStorageService {

    private final ApexClassRepository repository;

    public ApexStorageService(ApexClassRepository repository) {
        this.repository = repository;
    }

    public ApexClassEntity save(ApexClassEntity entity) {
        return repository.save(entity);
    }
}
