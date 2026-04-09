package com.atlas.worker.config;

import com.atlas.common.security.JwtTokenParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtTokenParser jwtTokenParser(@Value("${atlas.jwt.secret}") String secret) {
        return new JwtTokenParser(secret);
    }
}
