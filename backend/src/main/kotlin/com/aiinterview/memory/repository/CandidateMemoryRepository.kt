package com.aiinterview.memory.repository

import com.aiinterview.memory.model.CandidateMemoryProfile
import org.springframework.data.repository.reactive.ReactiveCrudRepository

interface CandidateMemoryRepository : ReactiveCrudRepository<CandidateMemoryProfile, String>
