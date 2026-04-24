package com.threadly.payments.charge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChargeRepository extends JpaRepository<Charge, String> {
    Optional<Charge> findByIdempotencyKey(String idempotencyKey);
}
