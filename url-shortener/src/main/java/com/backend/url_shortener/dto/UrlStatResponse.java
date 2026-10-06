package com.backend.url_shortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlStatResponse {
    private String originalUrl;
    private String shortCode;
    private int clickCount;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private boolean isActive;
    private String createdBy;
}
