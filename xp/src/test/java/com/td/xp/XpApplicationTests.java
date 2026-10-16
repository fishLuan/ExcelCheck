package com.td.xp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "settlement.database-url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1")
class XpApplicationTests {

	@Test
	void contextLoads() {
	}

}
