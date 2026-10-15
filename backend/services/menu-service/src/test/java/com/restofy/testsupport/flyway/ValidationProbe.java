package com.restofy.testsupport.flyway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Outside com.restofy.menu so the existing application test cannot scan this test fixture.
@Entity
@Table(name = "hibernate_probe", schema = "public")
public class ValidationProbe {

    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String note;

    protected ValidationProbe() { }
}
