package com.rideflow.pricing.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RateCardRepository extends JpaRepository<RateCardRow, UUID> {}
