package gitGenie.backend.repository;

import gitGenie.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.Optional;

public class UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByGithubId(Long githubId);
}
