package com.rideflow.identity.internal;

import com.rideflow.identity.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByPhone(String phone);

  boolean existsByPhone(String phone);

  boolean existsByRole(Role role);
}
