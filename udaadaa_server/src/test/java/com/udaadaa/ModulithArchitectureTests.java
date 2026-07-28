package com.udaadaa;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTests {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(UdaadaaApplication.class).verify();
    }
}
