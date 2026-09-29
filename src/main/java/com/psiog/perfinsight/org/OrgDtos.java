package com.psiog.perfinsight.org;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public final class OrgDtos {
    private OrgDtos() {}

    public record OfferingDto(Long id, String code, String name, String description) {
        public static OfferingDto of(Offering o) {
            return o == null ? null : new OfferingDto(o.getId(), o.getCode(), o.getName(), o.getDescription());
        }
    }

    public record TeamDto(Long id, String name, Long offeringId, Long managerId, String managerName) {
        public static TeamDto of(Team t) {
            return new TeamDto(t.getId(), t.getName(),
                    t.getOffering() == null ? null : t.getOffering().getId(),
                    t.getManager() == null ? null : t.getManager().getId(),
                    t.getManager() == null ? null : t.getManager().getFullName());
        }
    }

    public record AssociateDto(Long id, String employeeCode, String fullName, String email, Long teamId, String teamName, boolean active) {
        public static AssociateDto of(Associate a) {
            return new AssociateDto(a.getId(), a.getEmployeeCode(), a.getFullName(), a.getEmail(),
                    a.getTeam() == null ? null : a.getTeam().getId(),
                    a.getTeam() == null ? null : a.getTeam().getName(), a.isActive());
        }
    }

    public record ProjectDto(Long id, String code, String name, String description, OfferingDto offering, boolean active) {
        public static ProjectDto of(Project p) {
            return new ProjectDto(p.getId(), p.getCode(), p.getName(), p.getDescription(), OfferingDto.of(p.getOffering()), p.isActive());
        }
    }

    public record AssignmentDto(Long id, Long associateId, String associateName, Long projectId, String projectCode,
                                Persona persona, LocalDate validFrom, LocalDate validTo, Integer allocationPercent) {
        public static AssignmentDto of(ProjectAssignment pa) {
            return new AssignmentDto(pa.getId(), pa.getAssociate().getId(), pa.getAssociate().getFullName(),
                    pa.getProject().getId(), pa.getProject().getCode(), pa.getPersona(),
                    pa.getValidFrom(), pa.getValidTo(), pa.getAllocationPercent());
        }
    }

    public record OfferingRequest(@NotBlank String code, @NotBlank String name, String description) {}
    public record TeamRequest(@NotBlank String name, Long offeringId, Long managerId) {}
    public record AssociateRequest(String employeeCode, @NotBlank String fullName, @NotBlank @Email String email,
                                   String azureObjectId, Long teamId, Boolean active) {}
    public record ProjectRequest(@NotBlank String code, @NotBlank String name, String description,
                                 @NotNull Long offeringId, Boolean active) {}
    public record AssignmentRequest(@NotNull Long associateId, @NotNull Long projectId, @NotNull Persona persona,
                                    @NotNull LocalDate validFrom, LocalDate validTo, Integer allocationPercent) {}
    /** Convenience: close the current assignment and open a new one from a date (project move / promotion). */
    public record MoveRequest(@NotNull Long associateId, @NotNull Long toProjectId, @NotNull Persona persona,
                              @NotNull LocalDate effectiveFrom, Integer allocationPercent, Long closeAssignmentId) {}
}
