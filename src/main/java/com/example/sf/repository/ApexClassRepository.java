package com.example.sf.repository;

import com.example.sf.model.ApexClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApexClassRepository extends JpaRepository<ApexClassEntity, Long> {
    ApexClassEntity findByApexId(String apexId);
}
