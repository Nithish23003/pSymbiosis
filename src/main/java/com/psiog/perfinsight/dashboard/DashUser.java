package com.psiog.perfinsight.dashboard;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor
public class DashUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", unique = true, length = 50)
    private String employeeId;

    @Column(length = 100)
    private String name;

    @Column(length = 150)
    private String email;

    /** ADMIN | SERVICE_HEAD | MANAGER | EMPLOYEE */
    @Column(length = 50)
    private String role;

    private Boolean active = true;
}