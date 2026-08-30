package ai.utkarsh.pop.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface MonitoredServiceJpaRepository extends JpaRepository<MonitoredServiceJpaEntity, String> {
}
