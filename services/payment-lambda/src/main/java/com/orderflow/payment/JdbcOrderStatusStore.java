package com.orderflow.payment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Writes to the same `orders` table order-service owns.
 *
 * <p>Deliberate trade-off, chosen when this phase was planned: the payment
 * Lambda reaches into another service's table rather than calling an
 * order-service API. It keeps the flow simple, but it does mean the orders
 * schema now has two writers — changing that table requires checking here
 * too. The alternative (an internal PATCH endpoint on order-service) trades
 * this coupling for an authentication problem between the two.
 *
 * <p>A connection is opened per invocation rather than cached in a static
 * field. A cached connection survives between warm invocations but goes
 * stale (Aurora failover, idle timeout) with no easy way to notice, and
 * concurrent invocations each get their own execution environment anyway.
 * Production scale would put RDS Proxy in front of this to pool connections —
 * not available within this project's approved service list.
 */
public class JdbcOrderStatusStore implements OrderStatusStore {

    private final String url;
    private final String username;
    private final String password;

    public JdbcOrderStatusStore(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public int updateStatusIfPending(UUID orderId, String newStatus) {
        // "AND status = 'PENDING'" is the idempotency guard. EventBridge
        // invokes asynchronously and retries on failure, so this handler can
        // legitimately run twice for one event. Without the condition, a
        // retry would overwrite a terminal status and could double-charge.
        String sql = "UPDATE orders SET status = ?, updated_at = now() "
                + "WHERE id = ? AND status = 'PENDING'";

        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, newStatus);
            statement.setObject(2, orderId);
            return statement.executeUpdate();

        } catch (SQLException e) {
            // Let it propagate: EventBridge's async retry is the recovery
            // mechanism for a transient database problem.
            throw new IllegalStateException("Failed to update status for order " + orderId, e);
        }
    }
}
