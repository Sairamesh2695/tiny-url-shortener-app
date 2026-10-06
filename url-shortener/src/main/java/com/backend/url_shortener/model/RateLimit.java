package com.backend.url_shortener.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimit {
    private Integer minCount;
    private Integer hourCount;

    private LocalDateTime minWindowStart;
    private LocalDateTime hourWindowStart;
}
