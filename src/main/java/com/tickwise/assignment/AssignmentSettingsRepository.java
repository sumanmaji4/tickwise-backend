package com.tickwise.assignment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssignmentSettingsRepository extends JpaRepository<AssignmentSettings, UUID> {}
