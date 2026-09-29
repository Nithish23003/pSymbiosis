package com.psiog.perfinsight.notes;

import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.org.AssociateRepository;
import com.psiog.perfinsight.org.ProjectRepository;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.security.AccessScopeService;
import com.psiog.perfinsight.security.AppRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/context-notes")
@RequiredArgsConstructor
public class ContextNoteController {

    private final ContextNoteRepository notes;
    private final AssociateRepository associates;
    private final ProjectRepository projects;
    private final AccessScopeService access;
    private final AuditService audit;

    public record NoteRequest(@NotNull Long associateId, Long projectId, @NotNull ContextNoteType type,
                              @NotNull LocalDate startDate, @NotNull LocalDate endDate, String note,
                              Boolean excludeFromActiveDays, Boolean visibleToAssociate) {}

    public record NoteDto(Long id, Long associateId, String associateName, Long projectId, String projectCode,
                          ContextNoteType type, LocalDate startDate, LocalDate endDate, String note,
                          boolean excludeFromActiveDays, boolean visibleToAssociate, String createdBy, Instant createdAt) {
        public static NoteDto of(ContextNote n) {
            return new NoteDto(n.getId(), n.getAssociate().getId(), n.getAssociate().getFullName(),
                    n.getProject() == null ? null : n.getProject().getId(),
                    n.getProject() == null ? null : n.getProject().getCode(),
                    n.getType(), n.getStartDate(), n.getEndDate(), n.getNote(), n.isExcludeFromActiveDays(),
                    n.isVisibleToAssociate(), n.getCreatedBy(), n.getCreatedAt());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<NoteDto> list(@RequestParam Long associateId,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AccessScope s = access.current();
        if (!s.canSee(associateId)) throw new ForbiddenException("No access");
        boolean self = associateId.equals(s.associateId()) && !s.canEditData();
        return notes.findForAssociate(associateId, from, to).stream()
                .filter(n -> !self || n.isVisibleToAssociate())
                .map(NoteDto::of).toList();
    }

    @PostMapping
    @Transactional
    public NoteDto create(@Valid @RequestBody NoteRequest r) {
        return NoteDto.of(save(new ContextNote(), r, "CONTEXT_NOTE_CREATED"));
    }

    @PutMapping("/{id}")
    @Transactional
    public NoteDto update(@PathVariable Long id, @Valid @RequestBody NoteRequest r) {
        ContextNote n = notes.findById(id).orElseThrow(() -> new NotFoundException("ContextNote", id));
        requireManagerOf(n.getAssociate().getId());
        return NoteDto.of(save(n, r, "CONTEXT_NOTE_UPDATED"));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        ContextNote n = notes.findById(id).orElseThrow(() -> new NotFoundException("ContextNote", id));
        requireManagerOf(n.getAssociate().getId());
        notes.delete(n);
        audit.log("CONTEXT_NOTE_DELETED", "ContextNote", id, Map.of("associateId", n.getAssociate().getId()));
    }

    private ContextNote save(ContextNote n, NoteRequest r, String action) {
        requireManagerOf(r.associateId());
        if (r.endDate().isBefore(r.startDate())) throw new BadRequestException("endDate before startDate");
        n.setAssociate(associates.findById(r.associateId()).orElseThrow(() -> new NotFoundException("Associate", r.associateId())));
        n.setProject(r.projectId() == null ? null : projects.findById(r.projectId()).orElseThrow(() -> new NotFoundException("Project", r.projectId())));
        n.setType(r.type());
        n.setStartDate(r.startDate());
        n.setEndDate(r.endDate());
        n.setNote(r.note());
        n.setExcludeFromActiveDays(r.excludeFromActiveDays() == null || r.excludeFromActiveDays());
        n.setVisibleToAssociate(r.visibleToAssociate() == null || r.visibleToAssociate());
        notes.save(n);
        audit.log(action, "ContextNote", n.getId(), Map.of("associateId", r.associateId(), "type", r.type().name()));
        return n;
    }

    private void requireManagerOf(Long associateId) {
        AccessScope s = access.current();
        boolean managerRole = s.has(AppRole.MANAGER) || s.has(AppRole.SERVICE_HEAD) || s.has(AppRole.ADMIN);
        if (!managerRole || !s.canSee(associateId)) throw new ForbiddenException("Only the associate's manager (or above) can add context notes");
    }
}
