package com.backend.url_shortener.service;

import com.backend.url_shortener.dto.ShortenUrlReq;
import com.backend.url_shortener.dto.ShortenUrlResponse;
import com.backend.url_shortener.dto.UrlAnalyticsResponse;
import com.backend.url_shortener.dto.UrlStatResponse;
import com.backend.url_shortener.model.ClickEvent;
import com.backend.url_shortener.model.UrlData;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlShortenerService {
    private final RedisTemplate<String, Object> redisTemplate;

    private final Map<String, UrlData> urlMappings = new ConcurrentHashMap<>();

    private final Map<String, List<ClickEvent>> clickAnalytics = new ConcurrentHashMap<>();

    @Value("${url-shortener.base_url}")
    private String baseUrl;

    @Value("${url-shortener.short-code.length}")
    private int shortCodeLength;

    @Value("${url-shortener.short-code.max-attempts}")
    private int maxGenerationAttempts;

    @Value("${url-shortener.cache.ttl-minutes}")
    private int cacheTtlMinutes;

    private static final String BASE_62_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public String getClientIp(HttpServletRequest httpRequest) {
        String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
        if(xForwardedFor == null || xForwardedFor.isBlank()) {
            return httpRequest.getRemoteAddr();
        }
        return xForwardedFor.split(",")[0].trim();
    }

    public ShortenUrlResponse shortenUrl(ShortenUrlReq req, String clientIp) {
        String shortCode = req.getCustomAlias();

        if(shortCode == null || shortCode.isEmpty()) {
            shortCode = generateUniqueShortCode();
        }else{
            shortCode = shortCode.trim();
            if(isPresent(shortCode)){
                throw new IllegalArgumentException("Custom alias already exists: " + shortCode);
            }
        }
        UrlData urlData = buildAndSaveUrlData(req, shortCode, clientIp);
        cacheUrl(shortCode, req.getOriginalUrl());
        log.info("Created a short URl: {} -> {}", shortCode, req.getOriginalUrl());

        return ShortenUrlResponse.builder()
                .originalUrl(req.getOriginalUrl())
                .shortCode(shortCode)
                .shortUrl(buildShortUrl(shortCode))
                .createdAt(urlData.getCreatedAt())
                .expiresAt(urlData.getExpiresAt())
                .build();
    }

    private String buildShortUrl(String shortCode) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBaseUrl + "/api/" + shortCode;
    }

    private void cacheUrl(String shortCode, String originalUrl) {
        try{
            redisTemplate.opsForValue().set("url:"+shortCode, originalUrl, cacheTtlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Failed to cache URL for {}:{}", shortCode, e.getMessage());
        }
    }

    private UrlData buildAndSaveUrlData(ShortenUrlReq request, String shortCode, String ip) {
        UrlData urlData = UrlData.builder()
                .originalUrl(request.getOriginalUrl())
                .createdAt(LocalDateTime.now())
                .createdBy(ip)
                .shortCode(shortCode)
                .expiresAt(request.getExpiresAt())
                .clickCount(0)
                .clickEvents(new ArrayList<>())
                .isActive(true)
                .build();

        urlMappings.put(shortCode, urlData);
        clickAnalytics.put(shortCode, new ArrayList<>());

        return urlData;
    }

    private boolean isPresent(String shortCode) {
        return urlMappings.containsKey(shortCode);
    }

    private String generateUniqueShortCode() {
        for (int i = 0; i < maxGenerationAttempts; i++) {
            StringBuilder buffer = new StringBuilder();
            for (int j = 0; j < shortCodeLength; j++) {
                int idx = ThreadLocalRandom.current().nextInt(BASE_62_CHARS.length());
                buffer.append(BASE_62_CHARS.charAt(idx));
            }
            if(!isPresent(buffer.toString())) {
                return buffer.toString();
            }
        }
        throw new RuntimeException("Failed to generate a unique short code after "+maxGenerationAttempts+" trails");
    }

    public Optional<String> getOriginalUrl(String shortCode) {
        String cachedUrl = getCachedUrl(shortCode);
        if (cachedUrl != null) {
            return Optional.of(cachedUrl);
        }

        UrlData urlData = urlMappings.get(shortCode);
        if (urlData != null && urlData.isActive()) {
            if (isExpired(urlData)) {
                urlData.setActive(false);
                return Optional.empty();
            }

            cacheUrl(shortCode, urlData.getOriginalUrl());
            return Optional.of(urlData.getOriginalUrl());
        }

        return Optional.empty();
    }

    private boolean isExpired(UrlData urlData) {
        return urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String getCachedUrl(String shortCode) {
        try{
            return (String) redisTemplate.opsForValue().get(shortCode);
        } catch (Exception e) {
            log.warn("Failed to reach cache for {} : {}", shortCode, e.getMessage());
            return null;
        }
    }

    public void recordClick(String shortCode, String clientIp, String userAgent, String referrer) {
        UrlData urlData = urlMappings.getOrDefault(shortCode, null);

        if(urlData != null && urlData.isActive()) {
            urlData.setClickCount(urlData.getClickCount() + 1);

            ClickEvent clickEvent = ClickEvent.builder()
                    .timestamp(LocalDateTime.now())
                    .ipAddress(clientIp)
                    .userAgent(userAgent)
                    .referrer(referrer)
                    .build();

            clickAnalytics.get(shortCode).add(clickEvent);
            log.info("Recorded click for short code: {}", shortCode);
        }
    }

    public Optional<UrlStatResponse> getUrlStats(String shortCode) {
        UrlData urlData = urlMappings.get(shortCode);

        if(urlData == null) return Optional.empty();

        return Optional.of(
                UrlStatResponse.builder()
                        .shortCode(shortCode)
                        .originalUrl(urlData.getOriginalUrl())
                        .createdAt(urlData.getCreatedAt())
                        .expiresAt(urlData.getExpiresAt())
                        .clickCount(urlData.getClickCount())
                        .createdBy(urlData.getCreatedBy())
                        .isActive(urlData.isActive())
                        .build()
        );
    }

    public Optional<UrlAnalyticsResponse> getUrlAnalytics(String shortCode) {
        UrlData urlData = urlMappings.getOrDefault(shortCode, null);

        if(urlData == null) {
            return Optional.empty();
        }

        List<ClickEvent> clicks = clickAnalytics.getOrDefault(shortCode, new ArrayList<>());

        Map<String, Integer> clickedByReferrer = clicks.stream()
                                                    .filter(c -> c.getReferrer() != null)
                                                    .collect(Collectors.groupingBy(ClickEvent::getReferrer,
                                                    Collectors.summingInt(e -> 1)
                                                    ));

        Map<String, Integer> clickedByHour = clicks.stream()
                                                .collect(Collectors.groupingBy(c -> c.getTimestamp().getHour() + ":00",
                                                Collectors.summingInt(e -> 1)));

        Map<String, Integer> clickedByDay = clicks.stream()
                .collect(Collectors.groupingBy(c -> c.getTimestamp().toLocalDate().toString(),
                        Collectors.summingInt(e -> 1)));

        List<ClickEvent> recentClicks = clicks.stream().sorted((a,b) -> b.getTimestamp().compareTo(a.getTimestamp())).toList();


        return Optional.of(
                UrlAnalyticsResponse.builder()
                        .shortCode(shortCode)
                        .originalUrl(urlData.getOriginalUrl())
                        .totalClicks(urlData.getClickCount())
                        .createdAt(urlData.getCreatedAt())
                        .expiresAt(urlData.getExpiresAt())
                        .recent(urlData.getClickEvents())
                        .clicksByReferrer(clickedByReferrer)
                        .clicksByHour(clickedByHour)
                        .clicksByDay(clickedByDay)
                        .recent(recentClicks)
                        .build()
        );
    }

    public Boolean deleteUrl(String shortCode, Boolean isExpired) {
        UrlData urlData = urlMappings.getOrDefault(shortCode, null);

        if (urlData != null) {
            urlData.setActive(false);
            if (!isExpired) {
                deleteCacheUrl(shortCode);
            }else {
                cleanExpiredUrls();
            }
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    private void cleanExpiredUrls() {
        int cleanedCount = 0;

        LocalDateTime now = LocalDateTime.now();

        for(Map.Entry<String, UrlData> entry: urlMappings.entrySet()){
            UrlData urlData = entry.getValue();
            if(urlData.getExpiresAt() != null && urlData.getExpiresAt().isBefore(now) && urlData.isActive()){
                urlData.setActive(false);
                deleteCacheUrl(entry.getKey());
                cleanedCount++;
            }
        }

        if(cleanedCount > 0){
            log.info("Cleaned up {} expired URLs", cleanedCount);
        }
    }

    private void deleteCacheUrl(String shortCode) {
        try {
            String url = "url:"+shortCode;
            redisTemplate.delete(url);
        } catch (Exception e) {
            log.warn("Failed to delete cached URL for {} : {}", shortCode, e.getMessage());
        }
    }
}
