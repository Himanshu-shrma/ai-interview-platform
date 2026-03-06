package com.aiinterview.shared.domain

enum class OrgType { PERSONAL, COMPANY, UNIVERSITY }

enum class UserRole { CANDIDATE, RECRUITER, ADMIN }

enum class InterviewType { DSA, CODING, SYSTEM_DESIGN, BEHAVIORAL }

enum class SessionStatus { PENDING, ACTIVE, COMPLETED, ABANDONED, EXPIRED }

enum class Difficulty { EASY, MEDIUM, HARD }

enum class MessageRole { AI, CANDIDATE, SYSTEM }

enum class SubmissionStatus {
    PENDING, RUNNING, ACCEPTED, WRONG_ANSWER,
    TIME_LIMIT, RUNTIME_ERROR, COMPILE_ERROR
}

/**
 * Canonical interview category used for question routing and generation.
 * Separate from InterviewType (which maps to the V3 Postgres enum).
 * CASE_STUDY has no matching Postgres enum value and maps to type=CODING in the DB.
 */
enum class InterviewCategory { CODING, DSA, BEHAVIORAL, SYSTEM_DESIGN, CASE_STUDY }
