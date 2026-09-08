package io.github.thescarletarrow.dbmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DbMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbMcpApplication.class, args);
    }
}
