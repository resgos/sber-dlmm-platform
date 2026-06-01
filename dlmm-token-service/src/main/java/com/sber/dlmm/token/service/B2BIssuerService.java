package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.B2BIssuer;
import com.sber.dlmm.token.repository.B2BIssuerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 5 #5.6 — B2B portal issuer management.
 *
 * <p>Prototype flow:
 * <ol>
 *   <li>Corp client calls {@link #register} → row inserted PENDING.</li>
 *   <li>Admin reviews and calls {@link #approve} or {@link #reject}.</li>
 *   <li>APPROVED issuers can create tokens (Sprint 6 — endpoint exists
 *       but token-creation linkage is not yet enforced; tracked in
 *       Sprint 6 backlog).</li>
 * </ol>
 *
 * <p>Real KYB pipeline (СберКорп Биометрия, ЕГРЮЛ check, sanctions
 * screening) plugs into {@link #approve} as a pre-condition in Sprint 7+.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class B2BIssuerService {

    private final B2BIssuerRepository issuerRepository;

    /**
     * Register a prospective corporate issuer in PENDING KYB state.
     *
     * <p>Idempotent-ish by INN. Re-registering with the same INN returns the
     * existing row (regardless of status) so corp client retries don't
     * duplicate. Production may want a stricter "PENDING duplicate =
     * error" rule depending on KYB SLA.
     *
     * @param inn          taxpayer number; the de-dup key
     * @param legalName    registered legal entity name
     * @param displayName  public-facing display name
     * @param contactEmail KYB contact email
     * @param contactPhone KYB contact phone
     * @param tier         requested service tier; defaults to {@code BASIC} when null
     * @return the existing issuer on INN match, otherwise the newly persisted row
     */
    @Transactional
    public B2BIssuer register(String inn, String legalName, String displayName,
                               String contactEmail, String contactPhone, B2BIssuer.Tier tier) {
        Optional<B2BIssuer> existing = issuerRepository.findByInn(inn);
        if (existing.isPresent()) {
            log.info("B2B issuer register: INN={} already exists id={} status={}",
                    inn, existing.get().getId(), existing.get().getKybStatus());
            return existing.get();
        }
        B2BIssuer issuer = B2BIssuer.builder()
                .inn(inn)
                .legalName(legalName)
                .displayName(displayName)
                .contactEmail(contactEmail)
                .contactPhone(contactPhone)
                .tier(tier != null ? tier : B2BIssuer.Tier.BASIC)
                .kybStatus(B2BIssuer.KybStatus.PENDING)
                .build();
        B2BIssuer saved = issuerRepository.save(issuer);
        log.info("B2B issuer registered id={} INN={} tier={}", saved.getId(), inn, saved.getTier());
        return saved;
    }

    /**
     * Approve an issuer's KYB, recording the reviewer + timestamp and clearing any
     * prior rejection reason. Idempotent: a second approve on an already-APPROVED
     * issuer is a no-op returning the unchanged row.
     *
     * @param issuerId   issuer to approve
     * @param reviewerId admin who performed the review (stored for audit)
     * @return the approved issuer row
     * @throws IllegalArgumentException if no issuer exists for {@code issuerId}
     */
    @Transactional
    public B2BIssuer approve(UUID issuerId, UUID reviewerId) {
        B2BIssuer issuer = mustFind(issuerId);
        if (issuer.getKybStatus() == B2BIssuer.KybStatus.APPROVED) {
            log.info("B2B issuer {} already APPROVED — no-op", issuerId);
            return issuer;
        }
        issuer.setKybStatus(B2BIssuer.KybStatus.APPROVED);
        issuer.setReviewedBy(reviewerId);
        issuer.setReviewedAt(LocalDateTime.now());
        issuer.setRejectionReason(null);
        B2BIssuer saved = issuerRepository.save(issuer);
        log.info("B2B issuer APPROVED id={} INN={} reviewedBy={}",
                saved.getId(), saved.getInn(), reviewerId);
        return saved;
    }

    /**
     * Reject an issuer's KYB, recording reviewer, timestamp and reason. Unlike
     * {@link #approve} this is not guarded — re-rejecting overwrites the prior
     * reason/reviewer with the latest decision.
     *
     * @param issuerId   issuer to reject
     * @param reviewerId admin who performed the review (stored for audit)
     * @param reason     human-readable rejection reason
     * @return the rejected issuer row
     * @throws IllegalArgumentException if no issuer exists for {@code issuerId}
     */
    @Transactional
    public B2BIssuer reject(UUID issuerId, UUID reviewerId, String reason) {
        B2BIssuer issuer = mustFind(issuerId);
        issuer.setKybStatus(B2BIssuer.KybStatus.REJECTED);
        issuer.setReviewedBy(reviewerId);
        issuer.setReviewedAt(LocalDateTime.now());
        issuer.setRejectionReason(reason);
        B2BIssuer saved = issuerRepository.save(issuer);
        log.info("B2B issuer REJECTED id={} INN={} reviewedBy={} reason={}",
                saved.getId(), saved.getInn(), reviewerId, reason);
        return saved;
    }

    /**
     * @return all issuers across every KYB status (admin listing)
     */
    @Transactional(readOnly = true)
    public List<B2BIssuer> findAll() {
        return issuerRepository.findAll();
    }

    /**
     * @param status KYB status to filter by
     * @return issuers in the given KYB status (e.g. the PENDING review queue)
     */
    @Transactional(readOnly = true)
    public List<B2BIssuer> findByStatus(B2BIssuer.KybStatus status) {
        return issuerRepository.findByKybStatus(status);
    }

    /**
     * @param id issuer id
     * @return the issuer
     * @throws IllegalArgumentException if no issuer exists for {@code id}
     */
    @Transactional(readOnly = true)
    public B2BIssuer findById(UUID id) {
        return mustFind(id);
    }

    /**
     * Loads an issuer or fails loudly — the single lookup-or-throw helper the
     * mutating/read methods route through.
     *
     * @param id issuer id
     * @return the issuer entity
     * @throws IllegalArgumentException if no issuer exists for {@code id}
     */
    private B2BIssuer mustFind(UUID id) {
        return issuerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("B2B issuer not found: " + id));
    }
}
