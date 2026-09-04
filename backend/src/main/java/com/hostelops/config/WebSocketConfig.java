package com.hostelops.config;

import com.hostelops.security.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * The real-time layer.
 *
 * <h2>What STOMP adds</h2>
 * A raw WebSocket is just a two-way pipe of bytes - it has no notion of "channels" or "who is
 * listening to what". STOMP is a small text protocol on top that adds destinations and
 * subscriptions, so a client can say "send me everything about wing B, first floor" and the server
 * can address one specific user. Without it we would be inventing that framing ourselves.
 *
 * <h2>Two channels, two audiences</h2>
 * <ul>
 *   <li><strong>{@code /topic/floors/{wing}-{floor}}</strong> - public. Anyone browsing that floor
 *       gets bed status changes. Carries no identities at all, so there is nothing to withhold from
 *       anyone.</li>
 *   <li><strong>{@code /user/queue/requests}</strong> - private. Only the student whose own request
 *       was approved, rejected or expired.</li>
 * </ul>
 *
 * <h2>Nothing is ever sent INBOUND over STOMP</h2>
 * There is no {@code @MessageMapping} anywhere in this project. Every state change goes through the
 * REST API, which already has authentication, authorization, validation and the error envelope
 * working. Accepting commands over the socket too would mean duplicating all of that on a second
 * surface, for no gain - and the REST call still works when the socket drops.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           @Value("${hostelops.cors.allowed-origins}") String allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // A simple in-memory broker: it holds subscriptions in this JVM's memory and fans messages
        // out itself. Perfect for one instance. Running several instances would need a real broker
        // (RabbitMQ/ActiveMQ) so a message published on instance A reaches a client connected to
        // instance B - worth knowing as the limit of this choice rather than discovering it later.
        registry.enableSimpleBroker("/topic", "/queue");

        // Prefix for inbound messages. Nothing uses it, deliberately - see the class comment.
        registry.setApplicationDestinationPrefixes("/app");

        // The prefix that makes per-user delivery work. See RealtimePublisher for why it is safe.
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Native WebSocket - what every current browser uses.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);

        // SockJS fallback on the same path, for networks or proxies that block WebSocket upgrades
        // (some corporate ones still do). It emulates the connection over plain HTTP.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Authenticates the CONNECT frame. Without this every socket would be anonymous, and
        // /user/queue/... would have no user to address.
        registration.interceptors(authInterceptor);
    }
}
