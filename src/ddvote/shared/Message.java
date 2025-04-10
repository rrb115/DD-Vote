package ddvote.shared; // CHANGED

import java.io.Serializable;
import java.util.List; // Keep standard import

// Class definition remains the same
public class Message implements Serializable {
    private static final long serialVersionUID = 1L; // Keep or update as needed
    private final MessageType type;
    private final Object payload;

    public Message(MessageType type, Object payload) { this.type = type; this.payload = payload; }
    public MessageType getType() { return type; }
    public Object getPayload() { return payload; }

    @Override
    public String toString() {
        String payloadStr;
        if (payload == null) payloadStr = "null";
            // Check specific types if needed for logging (Credentials is handled)
        else if (payload instanceof ddvote.shared.Credentials) payloadStr = payload.toString(); // Use Credentials' toString
        else if (payload instanceof List) payloadStr = "List[size=" + ((List<?>) payload).size() + "]";
        else payloadStr = payload.getClass().getSimpleName(); // Default to class name

        return "Message{type=" + type + ", payload=" + payloadStr + '}';
    }
}