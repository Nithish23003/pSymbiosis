package com.psiog.perfinsight.identity;

import com.psiog.perfinsight.activity.Activity;
import com.psiog.perfinsight.activity.ActivityRepository;
import com.psiog.perfinsight.activity.AttributionService;
import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.ingest.ToolType;
import com.psiog.perfinsight.org.Associate;
import com.psiog.perfinsight.org.AssociateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Acceptance criterion 4. Links tool accounts to one associate profile:
 *  1. exact email (corporate or alias)  2. known username alias  3. email local-part == username
 *  4. fuzzy display name (Jaro-Winkler >= 0.93, unique best)  -> otherwise UNMATCHED and reported.
 * MANUAL links always win and are never changed automatically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityResolutionService {

    private static final double FUZZY_THRESHOLD = 0.93;

    private final ToolAccountRepository accounts;
    private final AssociateAliasRepository aliases;
    private final AssociateRepository associates;
    private final ActivityRepository activities;
    private final AttributionService attribution;
    private final AuditService audit;
    private final JaroWinklerSimilarity jw = new JaroWinklerSimilarity();

    @Transactional
    public ToolAccount upsert(ToolType tool, ActorRef actor) {
        if (actor == null || actor.isEmpty()) return null;
        String key = actor.key();
        ToolAccount acc = accounts.findByToolTypeAndExternalId(tool, key).orElseGet(() -> {
            ToolAccount n = new ToolAccount();
            n.setToolType(tool);
            n.setExternalId(key);
            return n;
        });
        if (actor.username() != null) acc.setUsername(actor.username());
        if (actor.email() != null) acc.setEmail(actor.email().toLowerCase(Locale.ROOT));
        if (actor.displayName() != null) acc.setDisplayName(actor.displayName());
        boolean existing = acc.getId() != null;
        boolean wasUnmatched = acc.getMatchStatus() == null || acc.getMatchStatus() == MatchStatus.UNMATCHED;
        if (wasUnmatched) resolve(acc);
        ToolAccount saved = accounts.save(acc);
        // a later record (e.g. a commit carrying an email) resolved a previously unknown account:
        // pull its earlier activity across to the person too
        if (existing && wasUnmatched && saved.getAssociate() != null) reassignActivities(saved, saved.getAssociate());
        return saved;
    }

    void resolve(ToolAccount acc) {
        if (acc.getMatchStatus() == MatchStatus.MANUAL || acc.getMatchStatus() == MatchStatus.IGNORED) return;

        // 1. email
        if (acc.getEmail() != null) {
            Optional<Associate> byEmail = associates.findByEmailIgnoreCase(acc.getEmail());
            if (byEmail.isPresent()) { set(acc, byEmail.get(), MatchStatus.AUTO_EMAIL, 1.0, "Corporate email match"); return; }
            Optional<Associate> byAlias = aliasLookup(acc.getToolType(), acc.getEmail());
            if (byAlias.isPresent()) { set(acc, byAlias.get(), MatchStatus.AUTO_ALIAS, 0.98, "Alias email match"); return; }
        }
        // 2. username / external id alias
        for (String candidate : new String[]{acc.getUsername(), acc.getExternalId()}) {
            if (candidate == null) continue;
            Optional<Associate> byAlias = aliasLookup(acc.getToolType(), candidate);
            if (byAlias.isPresent()) { set(acc, byAlias.get(), MatchStatus.AUTO_ALIAS, 0.97, "Known alias '" + candidate + "'"); return; }
        }
        // 3. username == email local part
        if (acc.getUsername() != null) {
            String u = acc.getUsername().toLowerCase(Locale.ROOT);
            List<Associate> hits = associates.findAll().stream()
                    .filter(a -> a.getEmail().toLowerCase(Locale.ROOT).startsWith(u + "@")).toList();
            if (hits.size() == 1) { set(acc, hits.get(0), MatchStatus.AUTO_ALIAS, 0.9, "Username equals email local-part"); return; }
        }
        // 4. fuzzy name
        if (acc.getDisplayName() != null) {
            String n = normalise(acc.getDisplayName());
            Associate best = null;
            double bestScore = 0, second = 0;
            for (Associate a : associates.findAll()) {
                double s = jw.apply(n, normalise(a.getFullName()));
                if (s > bestScore) { second = bestScore; bestScore = s; best = a; }
                else if (s > second) second = s;
            }
            if (best != null && bestScore >= FUZZY_THRESHOLD && bestScore - second > 0.03) {
                set(acc, best, MatchStatus.AUTO_FUZZY, round(bestScore), "Display name similarity " + round(bestScore) + " - please confirm");
                return;
            }
        }
        acc.setAssociate(null);
        acc.setMatchStatus(MatchStatus.UNMATCHED);
        acc.setMatchConfidence(null);
        acc.setMatchNote("No email, alias or confident name match");
    }

    private Optional<Associate> aliasLookup(ToolType tool, String value) {
        return aliases.findByAliasValue(value.toLowerCase(Locale.ROOT)).stream()
                .filter(al -> al.getToolType() == null || al.getToolType() == tool)
                .map(AssociateAlias::getAssociate)
                .findFirst();
    }

    private void set(ToolAccount acc, Associate a, MatchStatus st, double conf, String note) {
        acc.setAssociate(a);
        acc.setMatchStatus(st);
        acc.setMatchConfidence(conf);
        acc.setMatchNote(note);
        acc.setLinkedAt(Instant.now());
        acc.setLinkedBy("auto");
    }

    /** Manual correction: link (or re-link) an account and re-attribute all its activity. */
    @Transactional
    public ToolAccount link(Long accountId, Long associateId, String note, boolean addAlias) {
        ToolAccount acc = accounts.findById(accountId).orElseThrow(() -> new NotFoundException("ToolAccount", accountId));
        Associate a = associates.findById(associateId).orElseThrow(() -> new NotFoundException("Associate", associateId));
        Long previous = acc.getAssociate() == null ? null : acc.getAssociate().getId();
        acc.setAssociate(a);
        acc.setMatchStatus(MatchStatus.MANUAL);
        acc.setMatchConfidence(1.0);
        acc.setMatchNote(note == null ? "Linked manually" : note);
        acc.setLinkedBy(audit.currentActor());
        acc.setLinkedAt(Instant.now());
        accounts.save(acc);
        if (addAlias && acc.getUsername() != null) aliases.save(new AssociateAlias(a, acc.getToolType(), acc.getUsername()));

        int moved = reassignActivities(acc, a);
        Map<String, Object> details = new HashMap<>();
        details.put("associateId", associateId);
        details.put("previousAssociateId", previous);
        details.put("activitiesReattributed", moved);
        audit.log("IDENTITY_LINKED", "ToolAccount", accountId, details);
        return acc;
    }

    @Transactional
    public ToolAccount unlink(Long accountId, boolean ignore) {
        ToolAccount acc = accounts.findById(accountId).orElseThrow(() -> new NotFoundException("ToolAccount", accountId));
        acc.setAssociate(null);
        acc.setMatchStatus(ignore ? MatchStatus.IGNORED : MatchStatus.UNMATCHED);
        acc.setMatchNote(ignore ? "Ignored (bot / service account)" : "Unlinked manually");
        acc.setLinkedBy(audit.currentActor());
        acc.setLinkedAt(Instant.now());
        accounts.save(acc);
        reassignActivities(acc, null);
        audit.log(ignore ? "IDENTITY_IGNORED" : "IDENTITY_UNLINKED", "ToolAccount", accountId, Map.of());
        return acc;
    }

    /** Re-try automatic resolution for every unmatched account (e.g. after new associates / aliases were added). */
    @Transactional
    public int resolveUnmatched() {
        int fixed = 0;
        for (ToolAccount acc : accounts.findByMatchStatus(MatchStatus.UNMATCHED)) {
            resolve(acc);
            if (acc.getAssociate() != null) {
                accounts.save(acc);
                reassignActivities(acc, acc.getAssociate());
                fixed++;
            }
        }
        return fixed;
    }

    private int reassignActivities(ToolAccount acc, Associate a) {
        List<Activity> list = activities.findByToolAccountId(acc.getId());
        for (Activity act : list) {
            act.setAssociate(a);
            attribution.attribute(act);
        }
        activities.saveAll(list);
        return list.size();
    }

    private static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }
}
