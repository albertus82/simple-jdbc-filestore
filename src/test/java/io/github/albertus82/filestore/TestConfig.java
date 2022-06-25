package io.github.albertus82.filestore;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionManager;

@Configuration
public class TestConfig {

	@Bean
	DataSource dataSource() {
		//@formatter:off
		return new DriverManagerDataSource(
//				"jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
				"jdbc:oracle:thin:@localhost:1521/XEPDB1", "test", "test"
		);
		//@formatter:on
	}

	@Bean
	TransactionManager transactionManager(DataSource dataSource) {
		return new DataSourceTransactionManager(dataSource);
	}

	@Bean
	JdbcTemplate jdbcTemplate(DataSource dataSource) {
		return new JdbcTemplate(dataSource);
	}

}
