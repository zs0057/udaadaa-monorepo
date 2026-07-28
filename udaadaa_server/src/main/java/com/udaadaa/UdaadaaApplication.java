package com.udaadaa;

import org.springframework.boot.SpringApplication;
import org.springframework.modulith.Modulith;

@Modulith(systemName = "Udaadaa", sharedModules = "common")
public class UdaadaaApplication {

    public static void main(String[] args) {
        SpringApplication.run(UdaadaaApplication.class, args);
    }
}
