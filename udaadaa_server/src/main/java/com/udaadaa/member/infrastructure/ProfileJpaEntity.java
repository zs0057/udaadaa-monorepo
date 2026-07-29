package com.udaadaa.member.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profiles", schema = "public")
class ProfileJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private BigDecimal height;

    private BigDecimal weight;

    protected ProfileJpaEntity() {
    }

    UUID id() {
        return id;
    }

    String nickname() {
        return nickname;
    }

    Instant createdAt() {
        return createdAt;
    }

    BigDecimal height() {
        return height;
    }

    BigDecimal weight() {
        return weight;
    }

    void update(String nickname, BigDecimal height, BigDecimal weight) {
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (height != null) {
            this.height = height;
        }
        if (weight != null) {
            this.weight = weight;
        }
    }
}
