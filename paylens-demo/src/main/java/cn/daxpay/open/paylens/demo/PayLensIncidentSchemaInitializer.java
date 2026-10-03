package cn.daxpay.open.paylens.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@ConditionalOnProperty(prefix = "paylens.incidents", name = "enabled", havingValue = "true")
public class PayLensIncidentSchemaInitializer implements ApplicationRunner {
    private static final String VERSION = "V003";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public PayLensIncidentSchemaInitializer(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS paylens_schema_migration (version varchar(32) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM paylens_schema_migration WHERE version=?", Integer.class, VERSION);
        if (applied != null && applied > 0) return;
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V003__incident_workbench.sql"));
        populator.execute(dataSource);
        jdbc.update("INSERT INTO paylens_schema_migration(version) VALUES (?) ON CONFLICT DO NOTHING", VERSION);
    }
}
