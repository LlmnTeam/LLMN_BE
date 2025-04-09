package com.example.llmn.integration.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final StringRedisTemplate redisTemplate;

    // 데이터 저장 (유효 기간 존재)
    public void storeValue(String type, String id, String value, Long expirationTime) {
        redisTemplate.opsForValue().set(buildKey(type, id), value, expirationTime, TimeUnit.MILLISECONDS);
    }

    // 유효 기간 X
    public void storeValue(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void addSetElement(String key, Long value) {
        SetOperations<String, String> setOps = redisTemplate.opsForSet();
        setOps.add(key, String.valueOf(value));
    }

    public void setExpireValue(String type, String id, Long expirationTime){
        redisTemplate.expire(buildKey(type, id), expirationTime, TimeUnit.MILLISECONDS);
    }

    // 데이터 존재 여부
    public boolean isValueExist(String type, String id) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(type, id)));
    }

    public boolean isStoredValue(String type, String id, String value) {
        String storedValue = redisTemplate.opsForValue().get(buildKey(type, id));
        return (storedValue != null) && storedValue.equals(value);
    }

    public boolean isNotStoredValue(String type, String id, String value) {
        String storedValue = redisTemplate.opsForValue().get(buildKey(type, id));
        return (storedValue == null) || !storedValue.equals(value);
    }

    public void removeValue(String type, String id) { redisTemplate.delete(buildKey(type, id)); }

    public String getValueInString(String type, String id){
        return redisTemplate.opsForValue().get(buildKey(type, id));
    }

    public Double getValueInDouble(String key){
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Double.parseDouble(value) : 0.0;
    }

    public Long getValueInLong(String key){
        String value = redisTemplate.opsForValue().get(key);
        return value != null ? Long.parseLong(value) : 0L;
    }

    public Long getTTL(String type, String id) {
        Long ttl = redisTemplate.getExpire(buildKey(type, id), TimeUnit.SECONDS);
        return ttl != null ? ttl : -2L;  // TTL이 존재하지 않으면 -2 반환
    }

    private String buildKey(String type, String id){
        return type + ":" + id;
    }
}