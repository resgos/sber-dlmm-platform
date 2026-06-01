package com.sber.dlmm.user.repository;

import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;
import com.sber.dlmm.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link User} accounts.
 *
 * <p>Backs auth (login/registration lookups + uniqueness pre-checks on the
 * {@code sberId}/{@code email} business keys) and the admin users-list (paged
 * filtering by KYC status / role and free-text search). Inherits the standard
 * CRUD operations from {@link JpaRepository}; the methods below add the
 * domain-specific lookups.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Looks up a user by their unique Sber identifier. Primary login/lookup
     * path.
     *
     * @param sberId the Sber ecosystem identifier (unique business key)
     * @return the matching user, or empty if none exists
     */
    Optional<User> findBySberId(String sberId);

    /**
     * Looks up a user by email (case-sensitive, exact match). Used for
     * email-based login.
     *
     * @param email the email address to match
     * @return the matching user, or empty if none exists
     */
    Optional<User> findByEmail(String email);

    /**
     * Looks up a user by phone number (exact match, {@code +7XXXXXXXXXX}).
     *
     * @param phone the phone number to match
     * @return the matching user, or empty if none exists
     */
    Optional<User> findByPhone(String phone);

    /**
     * Existence check on the Sber identifier — used at registration to reject
     * a duplicate {@code sberId} before insert.
     *
     * @param sberId the Sber identifier to test
     * @return {@code true} if a user with this {@code sberId} already exists
     */
    boolean existsBySberId(String sberId);

    /**
     * Existence check on email — used at registration to reject a duplicate
     * email before insert.
     *
     * @param email the email address to test
     * @return {@code true} if a user with this email already exists
     */
    boolean existsByEmail(String email);

    /**
     * Returns a page of users in the given KYC state. Backs the admin
     * users-list KYC filter.
     *
     * @param status   the KYC status to filter by
     * @param pageable paging and sort parameters
     * @return a page of users whose {@code kycStatus} equals {@code status}
     */
    Page<User> findByKycStatus(KycStatus status, Pageable pageable);

    /**
     * Returns a page of users holding the given role. Backs the admin
     * users-list role filter.
     *
     * @param role     the authorization role to filter by
     * @param pageable paging and sort parameters
     * @return a page of users whose {@code role} equals {@code role}
     */
    Page<User> findByRole(UserRole role, Pageable pageable);

    /**
     * Case-insensitive substring search across first name, last name and
     * email (matches if the term appears in any of the three). Backs the
     * admin users-list search box.
     *
     * @param query    the search term (matched as {@code %query%}, lower-cased)
     * @param pageable paging and sort parameters
     * @return a page of users matching the term in name or email
     */
    @Query("SELECT u FROM User u WHERE " +
           "LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(u.lastName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<User> search(@Param("q") String query, Pageable pageable);
}
