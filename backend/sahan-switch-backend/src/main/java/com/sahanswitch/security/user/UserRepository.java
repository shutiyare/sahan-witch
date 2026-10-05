package com.sahanswitch.security.user;

import com.sahanswitch.security.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** The participant is loaded together with the user so login needs no open session. */
    @EntityGraph(attributePaths = "participant")
    Optional<User> findByUsername(String username);

    boolean existsByRole(Role role);
}
