package com.backend.url_shortener.service;

import com.backend.url_shortener.model.RateLimit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimiterService {
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${url-shortener.rate-limit.requests-per-minute}")
    private int requestsPerMinute;

    @Value("${url-shortener.rate-limit.requests-per-hour}")
    private int requestsPerHour;

    private final ConcurrentHashMap<String, RateLimit> rateLimitData = new ConcurrentHashMap<>();

    private static final String REDIS_KEY_PREFIX = "ratelimit:";
    public boolean isAllowed(String clientIp) {
        String redisKey = REDIS_KEY_PREFIX + clientIp;

        LocalDateTime now = LocalDateTime.now();

        RateLimit data = getRateLimitDataFromRedis(redisKey);

        if(data == null) {
            data = rateLimitData.computeIfAbsent(clientIp, k -> RateLimit.builder()
                    .minCount(0)
                    .hourCount(0)
                    .minWindowStart(now)
                    .hourWindowStart(now)
                    .build());
        }

        if (isWithinMinuteWindow(data,now)){
            if(data.getMinCount() >= requestsPerMinute) {
                log.warn("Minute limit exceeded for {}", clientIp);
                return false;
            }
        }else{
            data.setMinCount(0);
            data.setMinWindowStart(now);
        }

        if (isWithinHourWindow(data,now)){
            if(data.getHourCount() >= requestsPerHour) {
                log.warn("Hour limit exceeded for {}", clientIp);
                return false;
            }
        }else{
            data.setHourCount(0);
            data.setHourWindowStart(now);
        }
        data.setMinCount(data.getMinCount()+1);
        data.setHourCount(data.getHourCount()+1);

        saveRateLimitDataToRedis(redisKey, data);
        return true;
    }

    private void saveRateLimitDataToRedis(String redisKey, RateLimit data) {
        try {
            redisTemplate.opsForValue().set(redisKey, data, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("Failed to save rate limit data to Redis: {}", e.getMessage());
        }
    }

    private boolean isWithinHourWindow(RateLimit data, LocalDateTime now) {
        return data.getHourWindowStart() != null && ChronoUnit.HOURS.between(data.getHourWindowStart(),now) < 1;
    }

    private boolean isWithinMinuteWindow(RateLimit data, LocalDateTime now) {
        return data.getMinWindowStart() != null && ChronoUnit.MINUTES.between(data.getMinWindowStart(),now) < 1;
    }

    private RateLimit getRateLimitDataFromRedis(String redisKey) {
        try {
            return (RateLimit) redisTemplate.opsForValue().get(redisKey);
        } catch (Exception e) {
            log.warn("Failed to get rate limit from Redis: {}", e.getMessage());
            return null;
        }
    }

    public int getRemainingReq(String clientIp) {
        String redisKey = REDIS_KEY_PREFIX + clientIp;
        RateLimit data = getRateLimitDataFromRedis(redisKey);

        if (data == null) {
            return requestsPerMinute;
        }

        LocalDateTime now = LocalDateTime.now();

        if(!isWithinMinuteWindow(data, now)) {
            return requestsPerMinute;
        }

        return Math.max(0, requestsPerMinute - data.getMinCount());
    }

    public long getTimeUntilReset(String clientIp) {
        String redisKey = REDIS_KEY_PREFIX + clientIp;
        RateLimit data = getRateLimitDataFromRedis(redisKey);

        if (data == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();

        if(data.getMinCount() >= requestsPerMinute) {
            LocalDateTime nextMin = data.getMinWindowStart().plusMinutes(1);
            return ChronoUnit.SECONDS.between(now, nextMin);
        }

        if(data.getHourCount() >= requestsPerHour) {
            LocalDateTime nextHour = data.getHourWindowStart().plusHours(1);
            return ChronoUnit.SECONDS.between(now, nextHour);
        }

        return 0;
    }


}
