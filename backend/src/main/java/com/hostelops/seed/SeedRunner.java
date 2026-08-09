package com.hostelops.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.user.Role;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Loads the real building and the demo accounts into an empty database.
 *
 * <p>{@link ApplicationRunner} is a Spring Boot hook: the {@code run} method executes once, after
 * the application context is fully built (so Flyway has already migrated) and before the app starts
 * serving traffic.
 *
 * <p><strong>Everything here is idempotent.</strong> It checks whether data already exists and does
 * nothing if so, because this runs on every single startup - during development that is dozens of
 * times a day, and a seeder that blindly inserted would duplicate the whole building each time.
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final boolean seedDemoUsers;

    public SeedRunner(JdbcTemplate jdbcTemplate,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      ObjectMapper objectMapper,
                      @Value("${hostelops.seed.demo-users:true}") boolean seedDemoUsers) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.seedDemoUsers = seedDemoUsers;
    }

    /**
     * One room as it appears in {@code seed/rooms.json}.
     *
     * <p>{@code status} in the source file is deliberately ignored: bed status is derived from
     * bed_claims, and there is no status column to put it in.
     */
    private record SeedRoom(
            int room_number, String wing, String block, String floor,
            int floor_level, int position_in_floor,
            String room_type, String bathroom_type) {

        int capacity() {
            return "Double".equals(room_type) ? 2 : 1;
        }
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        seedRoomsAndBeds();
        if (seedDemoUsers) {
            seedDemoUsers();
        }
    }

    private void seedRoomsAndBeds() throws Exception {
        Integer existing = jdbcTemplate.queryForObject("SELECT count(*) FROM rooms", Integer.class);
        if (existing != null && existing > 0) {
            log.debug("Rooms already seeded ({} rows) - skipping", existing);
            return;
        }

        List<SeedRoom> rooms = readRooms();

        jdbcTemplate.batchUpdate("""
                INSERT INTO rooms (room_number, wing, block, floor, floor_level,
                                   position_in_floor, room_type, bathroom_type, capacity)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        SeedRoom room = rooms.get(i);
                        ps.setInt(1, room.room_number());
                        ps.setString(2, room.wing());
                        ps.setString(3, room.block());
                        ps.setString(4, room.floor());
                        ps.setInt(5, room.floor_level());
                        ps.setInt(6, room.position_in_floor());
                        ps.setString(7, room.room_type());
                        ps.setString(8, room.bathroom_type());
                        ps.setInt(9, room.capacity());
                    }

                    @Override
                    public int getBatchSize() {
                        return rooms.size();
                    }
                });

        // Beds are derived from rooms entirely inside the database, so no generated room id ever
        // travels back to Java. Every Single room gets bed 'A'; every Double additionally gets 'B'.
        int bedsA = jdbcTemplate.update("INSERT INTO beds (room_id, bed_label) SELECT id, 'A' FROM rooms");
        int bedsB = jdbcTemplate.update(
                "INSERT INTO beds (room_id, bed_label) SELECT id, 'B' FROM rooms WHERE capacity = 2");

        log.info("Seeded {} rooms and {} beds ({} single-occupancy + {} second beds in doubles)",
                rooms.size(), bedsA + bedsB, bedsA, bedsB);
    }

    private List<SeedRoom> readRooms() throws Exception {
        // ClassPathResource reads from src/main/resources, which also works once packaged into a
        // jar - a plain File would not, and that failure only appears after deployment.
        try (InputStream in = new ClassPathResource("seed/rooms.json").getInputStream()) {
            return objectMapper.readerForListOf(SeedRoom.class).readValue(in);
        }
    }

    /**
     * Creates one demo account per role, so an interviewer can sign in as any of the three without
     * a registration flow existing.
     *
     * <p>All three go through the same {@link PasswordEncoder} as any real account would - the
     * passwords are well known, but nothing about how they are stored or checked is special-cased.
     */
    private void seedDemoUsers() {
        createIfAbsent(User.student(
                "student@hostelops.demo", encode("Student@123"),
                "Demo Student", "1MS22CS001", "B.E. Computer Science"));

        createIfAbsent(User.admin(
                "admin@hostelops.demo", encode("Admin@123"),
                "Demo Admin"));

        createIfAbsent(User.guest(
                "guest@hostelops.demo", encode("Guest@123"),
                "Guest Reviewer"));
    }

    private void createIfAbsent(User user) {
        if (userRepository.findByEmailIgnoreCase(user.getEmail()).isPresent()) {
            return;
        }
        userRepository.save(user);
        log.info("Seeded demo {} account: {}", user.getRole(), user.getEmail());
    }

    private String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
