package com.vn.vitalcare.share.messaging;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Carries small messages between application nodes, over the database they
 * already share.
 *
 * <p>Postgres {@code LISTEN}/{@code NOTIFY} is the whole transport. It is not a
 * queue: a node that is down misses what was sent while it was down, and
 * nothing is retried. Consumers must treat a message as a hint to re-read
 * state rather than as the state itself — a missed notification costs a page
 * refresh, never a wrong answer.
 *
 * <h2>Why a connection of its own</h2>
 *
 * <p>{@code LISTEN} is a property of a session, not of a statement: a pooled
 * connection is handed back after every query, so it cannot hold a
 * subscription. This keeps one connection outside the pool — the cost of the
 * feature. Remember it when sizing {@code maximumPoolSize}.
 *
 * <h2>Receiving</h2>
 *
 * <p>Arrivals are republished as a local {@link RemoteEvent} rather than being
 * dispatched to registered handlers, so a handler that both consumed from the
 * bus and published to it never becomes a dependency cycle.
 */
@Component
public class PostgresEventBus implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PostgresEventBus.class);

    /** Lower case and unquoted, because Postgres folds channel names that way. */
    static final String CHANNEL = "vitalcare_events";

    /** How long a poll waits before looping to re-check {@code running}. */
    private static final int POLL_TIMEOUT_MS = 5_000;

    private static final long RECONNECT_DELAY_MS = 5_000;

    /**
     * Distinguishes this node's messages from everybody else's.
     *
     * <p>Postgres delivers a notification to every listener including the
     * sender, and a node has invariably already acted on its own change
     * directly. Skipping the echo keeps "this came from outside" honest.
     */
    private final String nodeId = UUID.randomUUID().toString().substring(0, 8);

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher events;
    private final String url;
    private final String username;
    private final String password;

    private volatile boolean running;
    private volatile Connection connection;
    private Thread listener;

    public PostgresEventBus(
            JdbcTemplate jdbcTemplate,
            ApplicationEventPublisher events,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        this.jdbcTemplate = jdbcTemplate;
        this.events = events;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    /**
     * Sends a message to every other node.
     *
     * <p>Call this outside a transaction. Inside one, {@code pg_notify} holds
     * the message until the commit — correct, but both callers here publish
     * from an {@code AFTER_COMMIT} listener, where the commit has already
     * happened.
     *
     * @param kind    what the message is about, e.g. {@code auth}. Must not
     *                contain a colon
     * @param payload the rest, whose meaning belongs to whoever handles that
     *                kind. Keep it short; Postgres caps a notification at 8000
     *                bytes
     */
    public void publish(String kind, String payload) {
        try {
            jdbcTemplate.queryForObject(
                    "select pg_notify(?, ?)", String.class, CHANNEL, "%s:%s:%s".formatted(nodeId, kind, payload));
        } catch (RuntimeException e) {
            log.warn("Could not publish {} event — other nodes will not hear about it", kind, e);
        }
    }

    @Override
    public void start() {
        running = true;
        listener = Thread.ofPlatform()
                .name("postgres-event-bus")
                .daemon()
                .start(this::listen);
    }

    @Override
    public void stop() {
        running = false;
        Connection open = connection;
        if (open != null) {
            try {
                open.close();
            } catch (Exception e) {
                log.debug("Closing the notification connection failed", e);
            }
        }
        if (listener != null) {
            listener.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listen() {
        while (running) {
            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                connection = conn;
                try (Statement statement = conn.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                log.info("Listening on {} as node {}", CHANNEL, nodeId);

                PGConnection pg = conn.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_TIMEOUT_MS);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            receive(notification.getParameter());
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Event bus connection dropped; retrying in {}ms", RECONNECT_DELAY_MS, e);
                    sleep();
                }
            } finally {
                connection = null;
            }
        }
        log.info("Event bus stopped");
    }

    private void receive(String message) {
        String[] parts = message.split(":", 3);
        if (parts.length < 3 || nodeId.equals(parts[0])) {
            return;
        }
        log.debug("Received {} event from node {}", parts[1], parts[0]);
        events.publishEvent(new RemoteEvent(parts[1], parts[2]));
    }

    /**
     * A message that arrived from another node.
     *
     * <p>A distinct type from whatever the sender published locally, on
     * purpose. If a consumer could not tell the two apart it would re-broadcast
     * what it had just received, and two nodes would notify each other forever.
     */
    public record RemoteEvent(String kind, String payload) {
    }

    private static void sleep() {
        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
