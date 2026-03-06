package com.aiinterview.user.repository

import com.aiinterview.user.model.Organization
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import java.util.UUID

interface OrganizationRepository : ReactiveCrudRepository<Organization, UUID>
