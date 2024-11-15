package com.example.llmn.service;

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

    public void storeValue(String type, String value, Long expirationTime) {
        redisTemplate.opsForValue().set(type, value, expirationTime, TimeUnit.MILLISECONDS);
    }

    // 유효 기간 X
    public void storeValue(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void addSetElement(String key, Long value) {
        SetOperations<String, String> setOps = redisTemplate.opsForSet();
        setOps.add(key, String.valueOf(value));
    }


    public void setExpireDate(String type, String id, Long expirationTime){
        redisTemplate.expire(buildKey(type, id), expirationTime, TimeUnit.MILLISECONDS);
    }

    // 데이터 존재 여부
    public boolean isDateExist(String type, String id) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(type, id)));
    }


    public boolean isValidValue(String type, String id, String value) {
        String storedValue = redisTemplate.opsForValue().get(buildKey(type, id));
        return (storedValue != null) && storedValue.equals(value);
    }

    public boolean isNotValidValue(String type, String id, String value) {
        String storedValue = redisTemplate.opsForValue().get(buildKey(type, id));
        return (storedValue == null) || !storedValue.equals(value);
    }

    // 데이터 삭제
    public void removeData(String type, String id) { redisTemplate.delete(buildKey(type, id)); }

    // String 반환
    public String getDataInStr(String type, String id){ return redisTemplate.opsForValue().get(buildKey(type, id)); }


    // Double 반환
    public Double getDataInDouble(String key){
        String value = redisTemplate.opsForValue().get(key);

        // 값이 없으면 0.0 반환
        if(value == null){
            return 0.0;
        }

        return Double.valueOf(value);
    }

    public Long getTTL(String type, String id) {
        Long ttl = redisTemplate.getExpire(buildKey(type, id), TimeUnit.SECONDS);
        return ttl != null ? ttl : -2L;  // TTL이 존재하지 않으면 -2 반환
    }

    private String buildKey(String type, String id){
        return type + ":" + id;
    }
}