package com.aiinterview.user.repository

import com.aiinterview.user.model.OrgInvitation
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Mono
import java.util.UUID

interface OrgInvitationRepository : ReactiveCrudRepository<OrgInvitation, UUID> {
    fun findByToken(token: String): Mono<OrgInvitation>
}
