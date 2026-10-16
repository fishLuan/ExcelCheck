package com.td.xp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password="})
class XpApplicationTests {

    @org.springframework.boot.test.context.TestConfiguration
    static class TestDatabase {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ImportService testImportService() throws Exception {return new ImportService("jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1");}
    }
	@Test
	void contextLoads() {
	}

}
