package com.psiog.perfinsight.identity;

import com.psiog.perfinsight.activity.ActivityRepository;
import com.psiog.perfinsight.activity.EffectiveActivity;
import com.psiog.perfinsight.activity.EffectiveActivityService;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.AssociateRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/identity")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','SERVICE_HEAD','MANAGER')")
public class IdentityController {

    private final ToolAccountRepository accounts;
    private final AssociateAliasRepository aliases;
    private final AssociateRepository associates;
    private final ActivityRepository activities;
    private final EffectiveActivityService effective;
    private final IdentityResolutionService identity;

    public record AccountDto(Long id, ToolType toolType, String externalId, String username, String email,
                             String displayName, Long associateId, String associateName, MatchStatus matchStatus,
                             Double matchConfidence, String matchNote, String linkedBy, Instant linkedAt, Long unmatchedActivityCount) {
        static AccountDto of(ToolAccount a, Long count) {
            return new AccountDto(a.getId(), a.getToolType(), a.getExternalId(), a.getUsername(), a.getEmail(),
                    a.getDisplayName(), a.getAssociate() == null ? null : a.getAssociate().getId(),
                    a.getAssociate() == null ? null : a.getAssociate().getFullName(), a.getMatchStatus(),
                    a.getMatchConfidence(), a.getMatchNote(), a.getLinkedBy(), a.getLinkedAt(), count);
        }
    }

    public record LinkRequest(@NotNull Long associateId, String note, Boolean addAlias) {}
    public record AliasRequest(@NotNull Long associateId, ToolType toolType, @NotBlank String value) {}

    @GetMapping("/accounts")
    @Transactional(readOnly = true)
    public List<AccountDto> list(@RequestParam(required = false) MatchStatus status,
                                 @RequestParam(required = false) Long associateId) {
        List<ToolAccount> list = associateId != null ? accounts.findByAssociateId(associateId)
                : status != null ? accounts.findByMatchStatus(status) : accounts.findAll();
        Map<Long, Long> counts = unmatchedCounts();
        return list.stream().map(a -> AccountDto.of(a, counts.getOrDefault(a.getId(), 0L))).toList();
    }

    /** The "unmatched activity" report: accounts nobody is linked to, with how much activity is waiting. */
    @GetMapping("/unmatched")
    @Transactional(readOnly = true)
    public Map<String, Object> unmatched(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) Long projectId) {
        Map<Long, Long> counts = unmatchedCounts();
        List<AccountDto> accs = accounts.findByMatchStatus(MatchStatus.UNMATCHED).stream()
                .map(a -> AccountDto.of(a, counts.getOrDefault(a.getId(), 0L)))
                .sorted(Comparator.comparing(AccountDto::unmatchedActivityCount).reversed())
                .toList();
        List<AccountDto> fuzzy = accounts.findByMatchStatus(MatchStatus.AUTO_FUZZY).stream()
                .map(a -> AccountDto.of(a, 0L)).toList();
        LocalDate f = from == null ? LocalDate.now().minusYears(1) : from;
        LocalDate t = to == null ? LocalDate.now() : to;
        List<EffectiveActivity> sample = effective.effective(activities.findUnmatched(f, t, projectId, PageRequest.of(0, 200)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unmatchedAccounts", accs);
        out.put("fuzzyMatchesToConfirm", fuzzy);
        out.put("unmatchedActivities", sample);
        out.put("unmatchedActivityTotal", counts.values().stream().mapToLong(Long::longValue).sum());
        return out;
    }

    @PostMapping("/accounts/{id}/link")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountDto link(@PathVariable Long id, @RequestBody LinkRequest req) {
        return AccountDto.of(identity.link(id, req.associateId(), req.note(), Boolean.TRUE.equals(req.addAlias())), 0L);
    }

    @PostMapping("/accounts/{id}/unlink")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountDto unlink(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean ignore) {
        return AccountDto.of(identity.unlink(id, ignore), 0L);
    }

    @PostMapping("/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> resolve() {
        return Map.of("newlyMatched", identity.resolveUnmatched());
    }

    @PostMapping("/aliases")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> addAlias(@RequestBody AliasRequest req) {
        var a = associates.findById(req.associateId()).orElseThrow(() -> new NotFoundException("Associate", req.associateId()));
        AssociateAlias saved = aliases.save(new AssociateAlias(a, req.toolType(), req.value()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("associateId", a.getId());
        out.put("toolType", saved.getToolType());
        out.put("value", saved.getAliasValue());
        out.put("newlyMatched", identity.resolveUnmatched());
        return out;
    }

    private Map<Long, Long> unmatchedCounts() {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] row : activities.countUnmatchedByAccount()) m.put((Long) row[0], (Long) row[1]);
        return m;
    }
}
