package io.github.albertus82.filestore;

import java.util.Date;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(TestConfig.class)
class SimpleJdbcFileStoreSpringTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void beforeAll() {
		Logger.getLogger("").setLevel(Level.FINE);
		for (final Handler h : Logger.getLogger("").getHandlers()) {
			h.setLevel(Level.FINE);
		}
	}

	@BeforeEach
	void beforeEach() {
		new ResourceDatabasePopulator(new ClassPathResource(SimpleJdbcFileStoreSpringTest.class.getPackageName().replace('.', '/') + "/table.sql")).execute(jdbcTemplate.getDataSource());
	}

	@AfterEach
	void afterEach() {
		jdbcTemplate.execute("DROP TABLE storage");
	}

	@Test
	void testDatabase1() {
		jdbcTemplate.update("INSERT INTO storage (filename, content_length, file_contents, last_modified, compressed) VALUES (?, ?, ?, ?, ?)", "a", 1, "x".getBytes(), new Date(), false);
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM storage", int.class));
	}

	@Test
	void testDatabase2() {
		jdbcTemplate.update("INSERT INTO storage (filename, content_length, file_contents, last_modified, compressed) VALUES (?, ?, ?, ?, ?)", "b", 2, "yz".getBytes(), new Date(), false);
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM storage", int.class));
	}

}
