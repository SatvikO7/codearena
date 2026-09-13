package com.codearena.contest;

import com.codearena.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One user's registration for one contest.
 *
 * <p>Deliberately thin: a pair and a timestamp. Everything else about a participant — score,
 * penalty, rank, which problems they solved — is derived from their submissions rather than
 * stored here, so there is no second copy of the truth to fall out of step with the first.
 *
 * <p>The uniqueness of {@code (contest, user)} is enforced by a database constraint rather
 * than by a check in a service. Two concurrent registration requests can both pass a check;
 * only one can win a unique index.
 */
@Entity
@Table(name = "contest_participants")
public class ContestParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false, updatable = false)
    private Contest contest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "registered_at", nullable = false, updatable = false, insertable = false)
    private Instant registeredAt;

    protected ContestParticipant() {
        // Required by JPA.
    }

    public static ContestParticipant of(Contest contest, User user) {
        ContestParticipant participant = new ContestParticipant();
        participant.contest = contest;
        participant.user = user;
        return participant;
    }

    public Long getId() {
        return id;
    }

    public Contest getContest() {
        return contest;
    }

    public User getUser() {
        return user;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }
}
