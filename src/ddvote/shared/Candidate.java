package ddvote.shared; // CHANGED

import java.io.Serializable;
import java.util.Objects;

// Class definition remains the same
public class Candidate implements Serializable {
    private static final long serialVersionUID = 2L;
    private final String id;
    private final String name;
    private final String description;

    public Candidate(String id, String name, String description) {
        if (id == null || id.trim().isEmpty() || name == null || name.trim().isEmpty()) throw new IllegalArgumentException("ID/Name required");
        this.id = id.trim(); this.name = name.trim(); this.description = (description != null) ? description.trim() : "";
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    @Override public String toString() { return name + (description.isEmpty() ? "" : " (" + description + ")"); }
    @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; return id.equals(((Candidate) o).id); }
    @Override public int hashCode() { return Objects.hash(id); }
}