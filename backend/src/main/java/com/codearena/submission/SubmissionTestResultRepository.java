package com.codearena.submission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubmissionTestResultRepository extends JpaRepository<SubmissionTestResult, Long> {

    /** One submission's results in test order. The only query this table ever needs. */
    List<SubmissionTestResult> findBySubmissionIdOrderByPositionAsc(Long submissionId);
}
