package com.hostelops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>{@code @SpringBootApplication} is three annotations in one, and it is worth knowing which:
 * <ul>
 *   <li>{@code @SpringBootConfiguration} - this class may declare beans.</li>
 *   <li>{@code @EnableAutoConfiguration} - Spring looks at what is on the classpath and wires up
 *       sensible defaults. Because postgresql + spring-boot-starter-data-jpa are dependencies, it
 *       builds a DataSource and a connection pool for us from the properties in application.yml.
 *       Nearly all the "magic" people mean when they say Spring Boot is magic is this one line.</li>
 *   <li>{@code @ComponentScan} - scan this package and everything under it for
 *       {@code @Component} / {@code @Service} / {@code @RestController} classes and register them.
 *       This is why every package in the project lives under {@code com.hostelops}.</li>
 * </ul>
 */
@SpringBootApplication
public class HostelOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HostelOpsApplication.class, args);
    }
}
