package com.hostelops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on {@code @Scheduled}.
 *
 * <p>Without this annotation somewhere in the application, every {@code @Scheduled} method is
 * silently ignored - no error, no warning, the job simply never runs. That failure mode is quiet
 * enough to cost an afternoon, which is why it gets its own file rather than being tucked onto
 * another class.
 *
 * <p>Phase 2 uses it for the token-revocation cleanup; Phase 6 adds the PENDING request expiry
 * sweep, which is the one that matters for the state machine.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
