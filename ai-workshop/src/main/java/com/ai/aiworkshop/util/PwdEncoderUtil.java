package com.ai.aiworkshop.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PwdEncoderUtil {

    public static void main(String[] args) {
        BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        System.out.println(passwordEncoder.encode("admin123456"));
    }
}
