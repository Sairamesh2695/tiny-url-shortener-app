package com.backend.url_shortener.controller;

import com.backend.url_shortener.dto.ShortenUrlReq;
import com.backend.url_shortener.dto.ShortenUrlResponse;
import com.backend.url_shortener.dto.UrlAnalyticsResponse;
import com.backend.url_shortener.dto.UrlStatResponse;
import com.backend.url_shortener.service.RateLimiterService;
import com.backend.url_shortener.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class UrlShortenerController {
    private final UrlShortenerService urlShortenerService;
    private final RateLimiterService rateLimiterService;

    @PostMapping("/shorten")
    public ResponseEntity<?> shortenUrl(@Valid @RequestBody ShortenUrlReq req,
                                        HttpServletRequest httpRequest) {
        String clientIp = urlShortenerService.getClientIp(httpRequest);
        if (!rateLimiterService.isAllowed(clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "Rate Limit Exceeded",
                            "remaining requests", rateLimiterService.getRemainingReq(clientIp),
                            "can request after ", rateLimiterService.getTimeUntilReset(clientIp) + "seconds"
                    ));
        }
        try {
            ShortenUrlResponse urlResponse = urlShortenerService.shortenUrl(req, clientIp);
            return ResponseEntity.ok(urlResponse);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Unable to process due to internal server error"));
        }
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirectToOriginalUrl(@PathVariable String shortCode,
                                                      HttpServletRequest request, HttpServletResponse response) {
        String clientIp = urlShortenerService.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        String referrer = request.getHeader("Referrer");

        Optional<String> originalUrl = urlShortenerService.getOriginalUrl(shortCode);

        if(originalUrl.isPresent()) {
            urlShortenerService.recordClick(shortCode, clientIp, userAgent, referrer);

            response.setHeader("Location", originalUrl.get());
            return ResponseEntity.status(HttpStatus.FOUND).build();
        }else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/stats/{shortCode}")
    public ResponseEntity<?> getUrlStats(@PathVariable String shortCode) {
        Optional<UrlStatResponse> stats = urlShortenerService.getUrlStats(shortCode);

        if(stats.isPresent()) {
            return ResponseEntity.ok(stats.get());
        }else{
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error","short code not found"));
        }
    }

    @GetMapping("/analytics/{shortCode}")
    public ResponseEntity<?> getUrlAnalytics(@PathVariable String shortCode) {
        Optional<UrlAnalyticsResponse> analytics = urlShortenerService.getUrlAnalytics(shortCode);

        if (analytics.isPresent()) {
            return ResponseEntity.ok(analytics.get());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "analytics for the given short code not present."));
        }
    }

    @DeleteMapping("/delete/{shortCode}")
    public ResponseEntity<?> deleteUrl(@PathVariable String shortCode, @RequestParam Boolean isExpired) {
        if (!isExpired) {
            Boolean deleted = urlShortenerService.deleteUrl(shortCode, Boolean.FALSE);
            if (deleted) {
                return ResponseEntity.ok(Map.of("message", "Shortened URL deleted successfully"));
            }
        }else {
            Boolean deleteExpired = urlShortenerService.deleteUrl(shortCode, Boolean.TRUE);
            if (deleteExpired) {
                return ResponseEntity.ok(Map.of("message", "Expired Shortened URL deleted successfully"));
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error","Short Code not found"));
    }
}
