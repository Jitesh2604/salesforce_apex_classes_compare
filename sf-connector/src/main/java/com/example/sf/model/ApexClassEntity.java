package com.example.sf.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "apex_classes")
public class ApexClassEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "apex_id", unique = true)
    private String apexId;

    @Column(name = "name")
    private String name;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getApexId() { return apexId; }
    public void setApexId(String apexId) { this.apexId = apexId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
