package com.aiinterview.user.repository

import com.aiinterview.user.model.User
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Mono
import java.util.UUID

interface UserRepository : ReactiveCrudRepository<User, UUID> {
    fun findByClerkUserId(clerkUserId: String): Mono<User>
    fun findByEmail(email: String): Mono<User>
}
