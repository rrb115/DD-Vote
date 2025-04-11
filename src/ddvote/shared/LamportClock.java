package ddvote.shared;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

// Simple Lamport Clock implementation
public class LamportClock implements Serializable {
    private static final long serialVersionUID = 10L; // Unique ID for serialization
    private final AtomicInteger clock = new AtomicInteger(0);

    /**
     * Increment the clock before a local event or sending a message.
     * @return The new timestamp after incrementing.
     */
    public int tick() {
        return clock.incrementAndGet();
    }

    /**
     * Update the local clock upon receiving a message with a timestamp.
     * Sets the local clock to max(local_time, received_timestamp) + 1.
     * @param receivedTimestamp The timestamp received in the message.
     * @return The new timestamp after updating.
     */
    public int update(int receivedTimestamp) {
        int currentVal;
        int newVal;
        do {
            currentVal = clock.get();
            // Lamport's rule: max(local, received) + 1
            newVal = Math.max(currentVal, receivedTimestamp) + 1;
        } while (!clock.compareAndSet(currentVal, newVal)); // Atomically update if still currentVal
        return newVal;
    }

    /**
     * Get the current clock time without incrementing.
     * @return The current timestamp.
     */
    public int getTime() {
        return clock.get();
    }

    @Override
    public String toString() {
        return String.valueOf(clock.get());
    }
}